package dev.acoustic.mc1122.forge

import dev.acoustic.api.material.FrequencyBands
import dev.acoustic.api.math.Vec3
import dev.acoustic.api.scene.AcousticScene
import dev.acoustic.core.compute.GeometricExternalBackend
import dev.acoustic.core.passes.EnvironmentRayPass
import dev.acoustic.core.passes.ReflectionField
import dev.acoustic.core.passes.ReflectionSample
import dev.acoustic.core.scene.ImmutableVoxelSnapshot
import dev.acoustic.core.trace.VoxelRaycast
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Collections
import org.lwjgl.BufferUtils
import org.lwjgl.opencl.CL10
import org.lwjgl.opencl.CLCommandQueue
import org.lwjgl.opencl.CLContext
import org.lwjgl.opencl.CLDevice
import org.lwjgl.opencl.CLKernel
import org.lwjgl.opencl.CLMem
import org.lwjgl.opencl.CLProgram

/** Optional batched OpenCL multi-bounce voxel ray accelerator. */
internal class OpenClGeometricBackend : GeometricExternalBackend {
    private var initialized = false
    private var available = false
    private var disabled = false
    private var validated = false
    private var description = "OpenCL ray backend not probed"
    private var lastFailure = ""
    private var traceCount = 0L
    private var failureCount = 0L
    private var lastTraceNanos = 0L

    private var device: CLDevice? = null
    private var context: CLContext? = null
    private var queue: CLCommandQueue? = null
    private var program: CLProgram? = null
    private var kernel: CLKernel? = null

    private var cachedScene: ImmutableVoxelSnapshot? = null
    private var cachedRevision = Long.MIN_VALUE
    private var sceneShapeOffset: CLMem? = null
    private var sceneShapeCount: CLMem? = null
    private var sceneBoxes: CLMem? = null
    private var sceneScatter: CLMem? = null
    private var sceneReflect: CLMem? = null
    private var tracePaths: CLMem? = null
    private var traceEnergies: CLMem? = null
    private var traceSlots = 0

    override fun id(): String = "opencl"
    override fun autoPriority(): Int = 100

    @Synchronized
    override fun description(): String {
        ensureInitializedQuietly()
        return description
    }

    @Synchronized
    override fun available(): Boolean {
        ensureInitializedQuietly()
        return available && !disabled
    }

    override fun supports(scene: AcousticScene, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): Boolean {
        if (scene.containsNonAirMedia() || scene !is ImmutableVoxelSnapshot) return false
        val cells = scene.sizeX().toLong() * scene.sizeY().toLong() * scene.sizeZ().toLong()
        val slots = rays.toLong() * bounces.toLong()
        return rays > 0 && rays <= 65536 && bounces > 0 && bounces <= 32 && cells > 0L &&
            cells <= 2_000_000L && slots <= 1_000_000L && maxDistance > 0.0 && maxDistance <= 256.0 && minEnergy >= 0.0
    }

    @Synchronized
    override fun preferredForAuto(scene: AcousticScene, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): Boolean {
        if (!supports(scene, rays, bounces, maxDistance, minEnergy)) return false
        val rayWork = rays.toLong() * bounces.toLong()
        val uploaded = scene === cachedScene && scene.revision() == cachedRevision && sceneShapeOffset != null
        return rayWork >= if (uploaded) 1024L else 3072L
    }

    @Synchronized override fun traceCount(): Long = traceCount
    @Synchronized override fun failureCount(): Long = failureCount
    @Synchronized override fun lastTraceMillis(): Double = if (lastTraceNanos <= 0L) Double.NaN else lastTraceNanos / 1_000_000.0
    @Synchronized override fun lastFailure(): String = lastFailure

    @Synchronized
    @Throws(Exception::class)
    override fun trace(scene: AcousticScene, listener: Vec3, rays: Int, bounces: Int, maxDistance: Double, minEnergy: Double): ReflectionField {
        ensureInitialized()
        if (!available || disabled) throw IllegalStateException(description)
        if (!supports(scene, rays, bounces, maxDistance, minEnergy)) throw IllegalArgumentException("geometric problem not supported by OpenCL backend")
        val started = System.nanoTime()
        var failed = false
        try {
            val snapshot = scene as ImmutableVoxelSnapshot
            ensureScene(snapshot)
            val slots = rays * bounces
            ensureTraceBuffers(slots)
            val pathInit = BufferUtils.createFloatBuffer(slots)
            for (i in 0 until slots) pathInit.put(i, -1f)
            check(CL10.clEnqueueWriteBuffer(requireNotNull(queue), requireNotNull(tracePaths), CL10.CL_TRUE, 0L, pathInit, null, null), "clEnqueueWriteBuffer(paths)")

            setObject(0, requireNotNull(sceneShapeOffset)); setObject(1, requireNotNull(sceneShapeCount)); setObject(2, requireNotNull(sceneBoxes))
            setObject(3, requireNotNull(sceneScatter)); setObject(4, requireNotNull(sceneReflect)); setObject(5, requireNotNull(tracePaths)); setObject(6, requireNotNull(traceEnergies))
            setInt(7, snapshot.minX()); setInt(8, snapshot.minY()); setInt(9, snapshot.minZ())
            setInt(10, snapshot.sizeX()); setInt(11, snapshot.sizeY()); setInt(12, snapshot.sizeZ())
            setFloat(13, listener.x.toFloat()); setFloat(14, listener.y.toFloat()); setFloat(15, listener.z.toFloat())
            setInt(16, rays); setInt(17, bounces); setFloat(18, maxDistance.toFloat()); setFloat(19, minEnergy.toFloat())

            val global = BufferUtils.createPointerBuffer(1)
            global.put(0, rays.toLong())
            check(CL10.clEnqueueNDRangeKernel(requireNotNull(queue), requireNotNull(kernel), 1, null, global, null, null, null), "clEnqueueNDRangeKernel")
            check(CL10.clFinish(requireNotNull(queue)), "clFinish")
            val pathOut = BufferUtils.createFloatBuffer(slots)
            val energyOut = BufferUtils.createFloatBuffer(slots * FrequencyBands.COUNT)
            check(CL10.clEnqueueReadBuffer(requireNotNull(queue), requireNotNull(tracePaths), CL10.CL_TRUE, 0L, pathOut, null, null), "clEnqueueReadBuffer(paths)")
            check(CL10.clEnqueueReadBuffer(requireNotNull(queue), requireNotNull(traceEnergies), CL10.CL_TRUE, 0L, energyOut, null, null), "clEnqueueReadBuffer(energies)")

            if (!validated) validateFirstHits(snapshot, listener, rays, bounces, maxDistance, pathOut, energyOut)
            val samples = ArrayList<ReflectionSample>()
            for (ray in 0 until rays) {
                val initial = EnvironmentRayPass.fibonacciDirection(ray, rays)
                for (bounce in 0 until bounces) {
                    val slot = ray * bounces + bounce
                    val distance = pathOut.get(slot)
                    if (distance < 0f) break
                    val energy = FloatArray(FrequencyBands.COUNT)
                    for (band in energy.indices) energy[band] = energyOut.get(slot * energy.size + band)
                    samples += ReflectionSample(distance.toDouble(), bounce + 1, initial, energy)
                }
            }
            traceCount++
            lastTraceNanos = System.nanoTime() - started
            lastFailure = ""
            return ReflectionField(rays, samples)
        } catch (t: Throwable) {
            failureCount++
            lastTraceNanos = System.nanoTime() - started
            lastFailure = shortMessage(t)
            disabled = true
            available = false
            description = baseDescription(description) + "; disabled after runtime failure: " + lastFailure
            failed = true
            if (t is Exception) throw t
            throw RuntimeException(t)
        } finally {
            if (failed) releasePersistent()
        }
    }

    private fun validateFirstHits(scene: ImmutableVoxelSnapshot, listener: Vec3, rays: Int, bounces: Int, maxDistance: Double, paths: FloatBuffer, energies: FloatBuffer) {
        val checks = minOf(16, rays)
        var hitChecks = 0
        for (c in 0 until checks) {
            val ray = if (checks == 1) 0 else ((c.toLong() * (rays - 1).toLong()) / (checks - 1).toLong()).toInt()
            val dir = EnvironmentRayPass.fibonacciDirection(ray, rays)
            val cpu = VoxelRaycast.firstSolid(scene, listener, dir, maxDistance)
            val slot = ray * bounces
            val gpuDistance = paths.get(slot)
            if (cpu == null) {
                if (gpuDistance >= 0f) throw IllegalStateException("OpenCL ray self-test false hit ray=$ray gpu=$gpuDistance")
                continue
            }
            hitChecks++
            if (gpuDistance < 0f) throw IllegalStateException("OpenCL ray self-test missed CPU hit ray=$ray cpu=${cpu.distance()}")
            val distanceError = kotlin.math.abs(gpuDistance.toDouble() - cpu.distance())
            val distanceTolerance = maxOf(0.002, kotlin.math.abs(cpu.distance()) * 2.0e-4)
            if (distanceError > distanceTolerance) throw IllegalStateException("OpenCL ray self-test distance mismatch ray=$ray cpu=${cpu.distance()} gpu=$gpuDistance")
            for (band in 0 until FrequencyBands.COUNT) {
                val expected = cpu.voxel().material().reflection(band).toDouble()
                val actual = energies.get(slot * FrequencyBands.COUNT + band).toDouble()
                if (!actual.isFinite() || kotlin.math.abs(actual - expected) > 2.0e-4) {
                    throw IllegalStateException("OpenCL ray self-test energy mismatch ray=$ray band=$band cpu=$expected gpu=$actual")
                }
            }
        }
        if (hitChecks > 0) {
            validated = true
            description = baseDescription(description) + "; self-test=pass"
        }
    }

    private fun ensureTraceBuffers(slots: Int) {
        if (tracePaths != null && traceSlots >= slots) return
        releaseTraceBuffers()
        tracePaths = createFloatBuffer(CL10.CL_MEM_READ_WRITE.toLong(), slots.toLong() * 4L)
        traceEnergies = createFloatBuffer(CL10.CL_MEM_WRITE_ONLY.toLong(), slots.toLong() * FrequencyBands.COUNT.toLong() * 4L)
        traceSlots = slots
    }

    private fun releaseTraceBuffers() {
        release(tracePaths); release(traceEnergies)
        tracePaths = null; traceEnergies = null; traceSlots = 0
    }

    private fun ensureScene(scene: ImmutableVoxelSnapshot) {
        if (cachedScene === scene && cachedRevision == scene.revision() && sceneShapeOffset != null) return
        releaseScene()
        val cells = scene.sizeX() * scene.sizeY() * scene.sizeZ()
        var totalBoxes = 0
        for (y in 0 until scene.sizeY()) for (z in 0 until scene.sizeZ()) for (x in 0 until scene.sizeX()) {
            totalBoxes += scene.voxelAt(scene.minX() + x, scene.minY() + y, scene.minZ() + z).shape().boxCount()
        }
        if (totalBoxes > 4_000_000) throw IllegalStateException("OpenCL acoustic shape table too large: $totalBoxes")

        val offsets = BufferUtils.createIntBuffer(cells)
        val counts = BufferUtils.createIntBuffer(cells)
        val boxData = BufferUtils.createFloatBuffer(maxOf(6, totalBoxes * 6))
        val scatter = BufferUtils.createFloatBuffer(cells)
        val reflect = BufferUtils.createFloatBuffer(cells * FrequencyBands.COUNT)
        var boxCursor = 0
        for (y in 0 until scene.sizeY()) for (z in 0 until scene.sizeZ()) for (x in 0 until scene.sizeX()) {
            val i = (y * scene.sizeZ() + z) * scene.sizeX() + x
            val voxel = scene.voxelAt(scene.minX() + x, scene.minY() + y, scene.minZ() + z)
            offsets.put(i, boxCursor)
            counts.put(i, voxel.shape().boxCount())
            for (q in 0 until voxel.shape().boxCount()) {
                val box = voxel.shape().box(q)
                val k = boxCursor * 6
                boxData.put(k, box.minX.toFloat()); boxData.put(k + 1, box.minY.toFloat()); boxData.put(k + 2, box.minZ.toFloat())
                boxData.put(k + 3, box.maxX.toFloat()); boxData.put(k + 4, box.maxY.toFloat()); boxData.put(k + 5, box.maxZ.toFloat())
                boxCursor++
            }
            scatter.put(i, if (voxel.solid()) voxel.material().scattering() else 0f)
            for (band in 0 until FrequencyBands.COUNT) {
                reflect.put(i * FrequencyBands.COUNT + band, if (voxel.solid()) voxel.material().reflection(band) else 1f)
            }
        }

        var newOffsets: CLMem? = null
        var newCounts: CLMem? = null
        var newBoxes: CLMem? = null
        var newScatter: CLMem? = null
        var newReflect: CLMem? = null
        try {
            newOffsets = createIntBuffer((CL10.CL_MEM_READ_ONLY or CL10.CL_MEM_COPY_HOST_PTR).toLong(), offsets)
            newCounts = createIntBuffer((CL10.CL_MEM_READ_ONLY or CL10.CL_MEM_COPY_HOST_PTR).toLong(), counts)
            newBoxes = createFloatBuffer((CL10.CL_MEM_READ_ONLY or CL10.CL_MEM_COPY_HOST_PTR).toLong(), boxData)
            newScatter = createFloatBuffer((CL10.CL_MEM_READ_ONLY or CL10.CL_MEM_COPY_HOST_PTR).toLong(), scatter)
            newReflect = createFloatBuffer((CL10.CL_MEM_READ_ONLY or CL10.CL_MEM_COPY_HOST_PTR).toLong(), reflect)
            sceneShapeOffset = newOffsets; sceneShapeCount = newCounts; sceneBoxes = newBoxes; sceneScatter = newScatter; sceneReflect = newReflect
            cachedScene = scene; cachedRevision = scene.revision()
        } catch (t: Throwable) {
            release(newOffsets); release(newCounts); release(newBoxes); release(newScatter); release(newReflect)
            throw t
        }
    }

    private fun ensureInitializedQuietly() {
        if (initialized) return
        try {
            ensureInitialized()
        } catch (t: Throwable) {
            available = false; disabled = true; lastFailure = shortMessage(t)
            description = "OpenCL ray backend unavailable: $lastFailure"
            releasePersistent()
        }
    }

    @Throws(Exception::class)
    private fun ensureInitialized() {
        if (initialized) {
            if (!available || disabled) throw IllegalStateException(description)
            return
        }
        initialized = true
        try {
            val selected = OpenClDeviceSelector.selectBestGpu()
            if (selected == null) {
                description = "OpenCL ray backend unavailable: no compiler-capable GPU device"
                return
            }
            val best = selected.device
            device = best
            val err = BufferUtils.createIntBuffer(1)
            context = CLContext.create(selected.platform, Collections.singletonList(best), err)
            check(err.get(0), "CLContext.create")
            queue = CL10.clCreateCommandQueue(requireNotNull(context), best, 0L, err)
            check(err.get(0), "clCreateCommandQueue")
            program = CL10.clCreateProgramWithSource(requireNotNull(context), KERNEL_SOURCE, err)
            check(err.get(0), "clCreateProgramWithSource")
            val build = CL10.clBuildProgram(requireNotNull(program), best, "", null)
            if (build != CL10.CL_SUCCESS) {
                var log = ""
                try { log = requireNotNull(program).getBuildInfoString(best, CL10.CL_PROGRAM_BUILD_LOG) } catch (_: Throwable) {}
                throw IllegalStateException("OpenCL ray kernel build failed code=$build $log")
            }
            kernel = CL10.clCreateKernel(requireNotNull(program), "acoustic_rays", err)
            check(err.get(0), "clCreateKernel")
            available = true
            description = "OpenCL ray GPU: ${selected.description}"
        } catch (t: Throwable) {
            available = false; disabled = true; lastFailure = shortMessage(t); releasePersistent()
            if (t is Exception) throw t
            throw RuntimeException(t)
        }
    }

    private fun createFloatBuffer(flags: Long, host: FloatBuffer): CLMem {
        val err = BufferUtils.createIntBuffer(1)
        val mem = CL10.clCreateBuffer(requireNotNull(context), flags, host, err)
        check(err.get(0), "clCreateBuffer(float host)")
        return mem
    }

    private fun createIntBuffer(flags: Long, host: IntBuffer): CLMem {
        val err = BufferUtils.createIntBuffer(1)
        val mem = CL10.clCreateBuffer(requireNotNull(context), flags, host, err)
        check(err.get(0), "clCreateBuffer(int host)")
        return mem
    }

    private fun createFloatBuffer(flags: Long, bytes: Long): CLMem {
        val err = BufferUtils.createIntBuffer(1)
        val mem = CL10.clCreateBuffer(requireNotNull(context), flags, bytes, err)
        check(err.get(0), "clCreateBuffer(size)")
        return mem
    }

    private val objectArg = BufferUtils.createByteBuffer(org.lwjgl.PointerBuffer.getPointerSize())
    private fun setObject(index: Int, value: CLMem) { org.lwjgl.PointerBuffer.put(objectArg, 0, value.pointer); check(CL10.clSetKernelArg(requireNotNull(kernel), index, objectArg), "clSetKernelArg[$index]") }
    private fun setInt(index: Int, value: Int) { val b = BufferUtils.createIntBuffer(1); b.put(0, value); check(CL10.clSetKernelArg(requireNotNull(kernel), index, b), "clSetKernelArg[$index]") }
    private fun setFloat(index: Int, value: Float) { val b = BufferUtils.createFloatBuffer(1); b.put(0, value); check(CL10.clSetKernelArg(requireNotNull(kernel), index, b), "clSetKernelArg[$index]") }

    @Synchronized
    override fun close() {
        disabled = true; available = false; releasePersistent()
    }

    private fun releaseScene() {
        release(sceneShapeOffset); release(sceneShapeCount); release(sceneBoxes); release(sceneScatter); release(sceneReflect)
        sceneShapeOffset = null; sceneShapeCount = null; sceneBoxes = null; sceneScatter = null; sceneReflect = null
        cachedScene = null; cachedRevision = Long.MIN_VALUE
    }

    private fun releasePersistent() {
        releaseTraceBuffers(); releaseScene()
        kernel?.let { try { CL10.clReleaseKernel(it) } catch (_: Throwable) {} }
        program?.let { try { CL10.clReleaseProgram(it) } catch (_: Throwable) {} }
        queue?.let { try { CL10.clReleaseCommandQueue(it) } catch (_: Throwable) {} }
        context?.let { try { CL10.clReleaseContext(it) } catch (_: Throwable) {} }
        kernel = null; program = null; queue = null; context = null; device = null
    }

    companion object {
        private const val KERNEL_SOURCE = """#define ACOUSTIC_INF 3.402823466e+38F
inline int ac_idx(int x,int y,int z,int minx,int miny,int minz,int nx,int ny,int nz){int lx=x-minx,ly=y-miny,lz=z-minz;if(lx<0||ly<0||lz<0||lx>=nx||ly>=ny||lz>=nz)return -1;return (ly*nz+lz)*nx+lx;}
inline int ac_sign(float v){return v>0.0f?1:(v<0.0f?-1:0);}
inline float ac_boundary(float o,int cell,int step,float d){if(step==0)return ACOUSTIC_INF;float edge=step>0?(float)(cell+1):(float)cell;return (edge-o)/d;}
inline int ac_box_hit(float3 o,float3 d,float3 mn,float3 mx,float minT,float maxT,float* outT,float3* outN){const float eps=1.0e-7f;float nx=-ACOUSTIC_INF,fx=ACOUSTIC_INF,ny=-ACOUSTIC_INF,fy=ACOUSTIC_INF,nz=-ACOUSTIC_INF,fz=ACOUSTIC_INF;if(fabs(d.x)<eps){if(o.x<mn.x-eps||o.x>mx.x+eps)return 0;}else{float a=(mn.x-o.x)/d.x,b=(mx.x-o.x)/d.x;nx=fmin(a,b);fx=fmax(a,b);}if(fabs(d.y)<eps){if(o.y<mn.y-eps||o.y>mx.y+eps)return 0;}else{float a=(mn.y-o.y)/d.y,b=(mx.y-o.y)/d.y;ny=fmin(a,b);fy=fmax(a,b);}if(fabs(d.z)<eps){if(o.z<mn.z-eps||o.z>mx.z+eps)return 0;}else{float a=(mn.z-o.z)/d.z,b=(mx.z-o.z)/d.z;nz=fmin(a,b);fz=fmax(a,b);}float near=fmax(nx,fmax(ny,nz)),far=fmin(fx,fmin(fy,fz));if(far+eps<near||far+eps<minT||near-eps>maxT)return 0;float3 n=(float3)(0.0f,0.0f,0.0f);if(near>=minT-eps){if(nx>=ny&&nx>=nz)n=(float3)(d.x>0.0f?-1.0f:1.0f,0,0);else if(ny>=nz)n=(float3)(0,d.y>0.0f?-1.0f:1.0f,0);else n=(float3)(0,0,d.z>0.0f?-1.0f:1.0f);}near=fmax(near,minT);far=fmin(far,maxT);if(far+eps<near)return 0;*outT=near;*outN=n;return 1;}
inline int ac_hit(__global const int* shapeOff,__global const int* shapeCount,__global const float* boxes,int minx,int miny,int minz,int nx,int ny,int nz,float3 origin,float3 dir,float maxdist,int* hitidx,float* hitdist,float3* normal){int x=(int)floor(origin.x),y=(int)floor(origin.y),z=(int)floor(origin.z);int sx=ac_sign(dir.x),sy=ac_sign(dir.y),sz=ac_sign(dir.z);float dx=sx==0?ACOUSTIC_INF:fabs(1.0f/dir.x),dy=sy==0?ACOUSTIC_INF:fabs(1.0f/dir.y),dz=sz==0?ACOUSTIC_INF:fabs(1.0f/dir.z);float tx=ac_boundary(origin.x,x,sx,dir.x),ty=ac_boundary(origin.y,y,sy,dir.y),tz=ac_boundary(origin.z,z,sz,dir.z),travel=0.0f;for(int guard=0;guard<4096;guard++){int i=ac_idx(x,y,z,minx,miny,minz,nx,ny,nz);if(i<0)return 0;float cellExit=fmin(maxdist,fmin(tx,fmin(ty,tz)));int off=shapeOff[i],count=shapeCount[i];float best=ACOUSTIC_INF;float3 bestN=(float3)(0,0,0);for(int q=0;q<count;q++){int bi=(off+q)*6;float3 mn=(float3)((float)x+boxes[bi],(float)y+boxes[bi+1],(float)z+boxes[bi+2]);float3 mx=(float3)((float)x+boxes[bi+3],(float)y+boxes[bi+4],(float)z+boxes[bi+5]);float t;float3 n;if(ac_box_hit(origin,dir,mn,mx,travel,cellExit,&t,&n)&&t<best){best=t;bestN=n;}}if(best<ACOUSTIC_INF){*hitidx=i;*hitdist=best;*normal=bestN;return 1;}if(cellExit>=maxdist)return 0;if(tx<=ty&&tx<=tz){travel=tx;tx+=dx;x+=sx;}else if(ty<=tz){travel=ty;ty+=dy;y+=sy;}else{travel=tz;tz+=dz;z+=sz;}}return 0;}
inline float3 ac_fib(int i,int count){const float golden=2.39996322972865332f;float y=1.0f-2.0f*(((float)i+0.5f)/(float)count);float r=sqrt(fmax(0.0f,1.0f-y*y));float phi=(float)i*golden;return (float3)(cos(phi)*r,y,sin(phi)*r);}
inline float3 ac_diffuse(int ray,int bounce,float3 normal){ulong seed=((ulong)(ray+1))*0x9E3779B97F4A7C15UL+((ulong)(bounce+11))*0xC2B2AE3D27D4EB4FUL;seed^=seed>>29;float u=(float)(seed&0xffffffUL)/16777216.0f;seed=seed*6364136223846793005UL+1442695040888963407UL;float v=(float)(seed&0xffffffUL)/16777216.0f;float z=u,r=sqrt(fmax(0.0f,1.0f-z*z)),phi=6.28318530717958648f*v;float3 d=(float3)(r*cos(phi),z,r*sin(phi));if(dot(d,normal)<0.0f)d=-d;return normalize(d);}
__kernel void acoustic_rays(__global const int* shapeOff,__global const int* shapeCount,__global const float* boxes,__global const float* scatter,__global const float* refl,__global float* paths,__global float* energies,int minx,int miny,int minz,int nx,int ny,int nz,float lx,float ly,float lz,int rays,int bounces,float maxdist,float minenergy){int ray=(int)get_global_id(0);if(ray>=rays)return;float3 initial=ac_fib(ray,rays),dir=initial,origin=(float3)(lx,ly,lz);float energy[8];for(int band=0;band<8;band++)energy[band]=1.0f;float total=0.0f;for(int bounce=1;bounce<=bounces;bounce++){int hi=-1;float hd=0.0f;float3 n;if(!ac_hit(shapeOff,shapeCount,boxes,minx,miny,minz,nx,ny,nz,origin,dir,maxdist,&hi,&hd,&n))return;total+=hd;int slot=ray*bounces+(bounce-1);int alive=0;for(int band=0;band<8;band++){energy[band]*=refl[hi*8+band];energies[slot*8+band]=energy[band];if(energy[band]>=minenergy)alive=1;}paths[slot]=total;if(!alive)return;float3 hitpos=origin+dir*hd;float dn=dot(dir,n);float3 spec=normalize(dir-n*(2.0f*dn));float s=scatter[hi];dir=s>0.0f?normalize(spec*(1.0f-s)+ac_diffuse(ray,bounce,n)*s):spec;origin=hitpos+n*1.0e-4f;}}
"""

        private fun release(mem: CLMem?) { if (mem != null) try { CL10.clReleaseMemObject(mem) } catch (_: Throwable) {} }
        private fun check(code: Int, op: String) { if (code != CL10.CL_SUCCESS) throw IllegalStateException("$op failed with OpenCL code $code") }
        private fun baseDescription(value: String): String { val i = value.indexOf("; disabled after runtime failure:"); return if (i < 0) value else value.substring(0, i) }
        private fun shortMessage(t: Throwable): String = t.message ?: t.javaClass.simpleName
    }
}
