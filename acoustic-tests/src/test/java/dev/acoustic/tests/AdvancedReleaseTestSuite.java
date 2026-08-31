package dev.acoustic.tests;

import dev.acoustic.api.material.AcousticMaterials;
import dev.acoustic.api.material.resolve.MaterialDescriptor;
import dev.acoustic.api.material.resolve.MaterialRule;
import dev.acoustic.core.material.MaterialResolver;
import dev.acoustic.core.material.infer.MaterialFacts;
import dev.acoustic.core.material.infer.InferredMaterial;
import dev.acoustic.core.material.infer.MaterialInferenceEngine;
import dev.acoustic.core.material.infer.GeneratedMaterialPackWriter;
import dev.acoustic.core.pack.AcousticMaterialResourceLoader;
import dev.acoustic.core.pack.AcousticMediumResourceLoader;
import dev.acoustic.core.pack.AcousticSourceResourceLoader;
import dev.acoustic.core.pack.SourceProfilePack;
import dev.acoustic.api.source.AcousticSourceProfile;
import dev.acoustic.core.source.profile.SourceProfileResolver;
import dev.acoustic.core.source.profile.DefaultSourceProfiles;
import dev.acoustic.core.pack.MaterialPack;
import dev.acoustic.api.math.Vec3;
import dev.acoustic.api.pipeline.AcceleratedPass;
import dev.acoustic.api.pipeline.Pass;
import dev.acoustic.api.pipeline.PassContext;
import dev.acoustic.api.pipeline.ParallelWorkExecutor;
import dev.acoustic.core.compute.FdtdBackendRegistry;
import dev.acoustic.core.compute.FdtdExternalBackend;
import dev.acoustic.core.compute.FdtdProblem;
import dev.acoustic.core.compute.GeometricBackendRegistry;
import dev.acoustic.core.compute.GeometricExternalBackend;
import dev.acoustic.core.material.MaterialResolverCompiler;
import dev.acoustic.core.medium.MediumResolver;
import dev.acoustic.core.pack.LoadedShaderPack;
import dev.acoustic.core.pack.ShaderPackLoader;
import dev.acoustic.core.passes.LegacyAcousticEvaluator;
import dev.acoustic.core.passes.LegacyEffectParameters;
import dev.acoustic.core.passes.HybridLegacyProjector;
import dev.acoustic.core.passes.LegacyRoomEstimate;
import dev.acoustic.core.passes.EnvironmentRayPass;
import dev.acoustic.core.passes.ReflectionField;
import dev.acoustic.core.passes.ReflectionSample;
import dev.acoustic.core.passes.StandardResources;
import dev.acoustic.core.pipeline.MapPassContext;
import dev.acoustic.core.pipeline.ParallelPipelineExecutor;
import dev.acoustic.core.pipeline.DefaultPipeline;
import dev.acoustic.core.runtime.AcousticRuntimeSession;
import dev.acoustic.core.runtime.StandardPipelineCompiler;
import dev.acoustic.mc1122.LegacyBlockSample;
import dev.acoustic.mc1122.LegacyPerformanceTuning;
import dev.acoustic.mc1122.LegacyRuntimeConfig;
import dev.acoustic.mc1122.LegacySceneCapture;
import dev.acoustic.mc1122.LegacyShaderPackRuntime;
import dev.acoustic.mc1122.LegacyWorldAccess;
import dev.acoustic.testkit.VoxelTestScene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Extra release-oriented checks kept separate from the original alpha regression suite. */
public final class AdvancedReleaseTestSuite {
    private AdvancedReleaseTestSuite() {}
    public static void main(String[] args) throws Exception {
        testLegacyEffectProjection();
        testOutdoorRoomSuppression();
        testRuntimeConfigRoundTrip();
        testLegacyShaderPackActivation();
        testReferencePresetOrderAndSinglePackModel();
        testShaderStageOverrides();
        testPresetDrivenPerformanceTuning();
        testParallelRoomProbeEquivalence();
        testUltraProfileUsesParallelFdtd();
        testParallelFdtdEquivalentToScalar();
        testHeterogeneousFdtdReducesToHomogeneous();
        testPartialFluidFdtdSampling();
        testExternalFdtdBackendSelectionAndFallback();
        testExternalGeometricBackendSelectionAndFallback();
        testAutoBackendPreferenceVsExplicitSelection();
        testBackendPriorityCudaBeforeOpenCl();
        testAcceleratorOverlapsIndependentCpuWork();
        testRollingSceneCaptureReuseAndSingleRead();
        testProgressiveInitialCaptureIsCenterOutAndBounded();
        testBudgetedSceneRefreshAvoidsRevisionChurn();
        testMaximumPresetQualityEnvelope();
        testCpuThreadOverrideIsBounded();
        testLegacyLivePipelineStopsAtHybrid();
        testFullHybridLegacyProjection();
        testStateSpecificMaterialRules();
        testUnknownBlockMaterialInference();
        testGeneratedMaterialPackRoundTrip();
        testResourcePackAcousticMaterialOverlay();
        testResourcePackAndShaderMediumOverlay();
        testSourceProfileParsingAndResolution();
        testResourcePackSourceProfileOverlay();
        testSourceProfileAffectsLegacyProjection();
        testExplicitNoShaderSelection();
        testHybridAutoCrossoverHonorsWaveTrust();
        testHybridTimeDomainWaveEntersRir();
        System.out.println("PASS: 35 advanced release tests");
    }

    private static void testLegacyEffectProjection() {
        VoxelTestScene room=VoxelTestScene.builder().boxShell(-5,0,-5,5,6,5,AcousticMaterials.STONE).build();
        LegacyAcousticEvaluator e=new LegacyAcousticEvaluator();Vec3 listener=new Vec3(0,2,0);LegacyRoomEstimate r=e.estimateRoom(room,listener,24);
        LegacyEffectParameters clear=e.evaluate(room,new Vec3(3,2,0),listener,r);
        LegacyEffectParameters blocked=e.evaluate(room,new Vec3(8,2,0),listener,r);
        if(clear.directGain()<0.99f)throw new AssertionError("clear direct path unexpectedly attenuated: "+clear.directGain());
        if(blocked.directGain()>=0.5f)throw new AssertionError("stone wall should strongly attenuate direct path: "+blocked.directGain());
        if(r.decayTimeSeconds()<=0.1f||r.density()<=0f)throw new AssertionError("room estimate invalid");
        System.out.println("[PASS] legacy EFX projection + room estimate");
    }

    private static void testOutdoorRoomSuppression() {
        LegacyAcousticEvaluator e=new LegacyAcousticEvaluator();Vec3 listener=new Vec3(0,2,0);
        VoxelTestScene.Builder openBuilder=VoxelTestScene.builder();
        for(int x=-30;x<=30;x++)for(int z=-30;z<=30;z++)openBuilder.solid(x,0,z,AcousticMaterials.SOIL);
        VoxelTestScene open=openBuilder.build();LegacyRoomEstimate openRoom=e.estimateRoom(open,listener,24);
        LegacyEffectParameters openFx=e.evaluate(open,new Vec3(4,2,0),listener,openRoom);
        VoxelTestScene.Builder forestBuilder=VoxelTestScene.builder();
        for(int x=-30;x<=30;x++)for(int z=-30;z<=30;z++)forestBuilder.solid(x,0,z,AcousticMaterials.SOIL);
        int[][] trunks={{-6,-5},{7,-4},{-8,7},{6,8},{10,2},{-3,11},{3,-10}};
        for(int[] t:trunks)for(int y=1;y<=7;y++)forestBuilder.solid(t[0],y,t[1],AcousticMaterials.WOOD);
        VoxelTestScene forest=forestBuilder.build();LegacyRoomEstimate forestRoom=e.estimateRoom(forest,listener,24);
        LegacyEffectParameters forestFx=e.evaluate(forest,new Vec3(4,2,0),listener,forestRoom);
        VoxelTestScene cave=VoxelTestScene.builder().boxShell(-8,0,-8,8,7,8,AcousticMaterials.STONE).build();
        LegacyRoomEstimate caveRoom=e.estimateRoom(cave,listener,24);LegacyEffectParameters caveFx=e.evaluate(cave,new Vec3(4,2,0),listener,caveRoom);
        if(openRoom.openness()<0.75f)throw new AssertionError("open field should classify as open: "+openRoom.openness());
        if(openRoom.decayTimeSeconds()>0.45f)throw new AssertionError("open field RT60 too long: "+openRoom.decayTimeSeconds());
        if(openFx.sendGain()>0.15f)throw new AssertionError("open field wet send too strong: "+openFx.sendGain());
        if(forestRoom.decayTimeSeconds()>0.85f)throw new AssertionError("forest should not sound cave-like: "+forestRoom.decayTimeSeconds());
        if(forestFx.sendGain()>0.22f)throw new AssertionError("forest wet send too strong: "+forestFx.sendGain());
        if(caveRoom.decayTimeSeconds()<1.0f)throw new AssertionError("stone cave should retain audible decay: "+caveRoom.decayTimeSeconds());
        if(caveFx.sendGain()<=forestFx.sendGain()*1.8f)throw new AssertionError("cave wet send should clearly exceed forest");
        System.out.println("[PASS] outdoor/forest late-reverb suppression vs enclosed cave");
    }

    private static void testRuntimeConfigRoundTrip() throws Exception {
        Path dir=Files.createTempDirectory("acoustic-runtime-config");Path f=dir.resolve("runtime.properties");
        LegacyRuntimeConfig d=LegacyRuntimeConfig.defaults();d.save(f);LegacyRuntimeConfig r=LegacyRuntimeConfig.loadOrCreate(f);
        if(!d.pack().equals(r.pack())||!d.profile().equals(r.profile())||d.horizontalRadius()!=r.horizontalRadius()||d.verticalRadius()!=r.verticalRadius()||d.effectsEnabled()!=r.effectsEnabled())throw new AssertionError("runtime config round-trip mismatch");
        Files.deleteIfExists(f);Files.deleteIfExists(dir);System.out.println("[PASS] legacy runtime config atomic round-trip");
    }

    private static void testLegacyShaderPackActivation() throws Exception {
        LegacyRuntimeConfig cfg=new LegacyRuntimeConfig("reference-pack","HIGH",18,10,10,24,true,false);
        LegacyShaderPackRuntime r=LegacyShaderPackRuntime.load(Paths.get("examples"),cfg);
        if(!"acoustic:reference".equals(r.pack().manifest().id()))throw new AssertionError("wrong pack id");
        if(!"HIGH".equals(r.profile()))throw new AssertionError("wrong preset");
        System.out.println("[PASS] 1.12.2 reference shader activation/validation");
    }

    private static void testReferencePresetOrderAndSinglePackModel() throws Exception {
        LoadedShaderPack base=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));
        java.util.List<String> expected=Arrays.asList("POTATO","LOW","MEDIUM","HIGH","ULTRA","MAXIMUM");
        if(!expected.equals(base.options().profiles()))throw new AssertionError("shader-local preset order mismatch: "+base.options().profiles());
        if(Files.exists(Paths.get("examples/performance-pack"))||Files.exists(Paths.get("examples/cinematic-pack")))throw new AssertionError("deprecated official packs still present");
        if(!"Reference Acoustic Shader".equals(base.manifest().name()))throw new AssertionError("unexpected reference display name");
        System.out.println("[PASS] one flexible reference shader + ordered shader-local presets");
    }

    private static void testShaderStageOverrides() throws Exception {
        LoadedShaderPack base=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));
        Map<String,String> overrides=new LinkedHashMap<String,String>();
        overrides.put("WAVE","OFF");overrides.put("DIFFRACTION","OFF");overrides.put("GEOMETRIC","OFF");overrides.put("EARLY_REFLECTIONS","OFF");overrides.put("LATE_REVERB","OFF");overrides.put("DIRECT_OCCLUSION","OFF");
        DefaultPipeline p=new StandardPipelineCompiler().compile(base,"MAXIMUM",overrides);
        for(Pass pass:p.passes()){
            String id=pass.id();
            if("standard.wave_low_frequency".equals(id)||"standard.diffraction".equals(id)||"standard.environment_rays".equals(id)||"standard.early_reflections".equals(id)||"standard.late_reverb".equals(id))throw new AssertionError("disabled stage remains in pipeline: "+id);
        }
        VoxelTestScene room=VoxelTestScene.builder().boxShell(-6,0,-6,6,6,6,AcousticMaterials.STONE).build();
        dev.acoustic.core.pipeline.ParallelPipelineExecutor executor=new dev.acoustic.core.pipeline.ParallelPipelineExecutor(2);
        try{
            dev.acoustic.core.pipeline.MapPassContext c=new dev.acoustic.core.pipeline.MapPassContext();
            c.put(dev.acoustic.core.passes.StandardResources.SCENE,room);c.put(dev.acoustic.core.passes.StandardResources.SOURCE_POSITION,new Vec3(2,2,0));c.put(dev.acoustic.core.passes.StandardResources.LISTENER_POSITION,new Vec3(0,2,0));
            executor.execute(p,c);
            if(c.get(dev.acoustic.core.passes.StandardResources.HYBRID_RESPONSE)==null)throw new AssertionError("hybrid response missing with optional stages disabled");
        }finally{executor.close();}
        System.out.println("[PASS] independently disabled propagation stages compile and execute");
    }

    private static void testPresetDrivenPerformanceTuning() throws Exception {
        LegacyShaderPackRuntime potato=LegacyShaderPackRuntime.load(Paths.get("examples"),new LegacyRuntimeConfig("reference-pack","POTATO",18,10,10,24,true,false));
        LegacyShaderPackRuntime maximum=LegacyShaderPackRuntime.load(Paths.get("examples"),new LegacyRuntimeConfig("reference-pack","MAXIMUM",18,10,10,24,true,false));
        LegacyPerformanceTuning p=potato.performanceTuning(),m=maximum.performanceTuning();
        if(!(p.horizontalRadius()<m.horizontalRadius()&&p.verticalRadius()<m.verticalRadius()&&p.roomRays()<m.roomRays()))throw new AssertionError("quality presets do not expand live budgets");
        if(!(p.captureIntervalTicks()>m.captureIntervalTicks()))throw new AssertionError("maximum should refresh more frequently");
        if(!(p.captureBudgetMillis()<m.captureBudgetMillis()))throw new AssertionError("maximum should receive a larger client capture time budget");
        if(!(potato.liveTuning().wetScale()<maximum.liveTuning().wetScale()))throw new AssertionError("preset live wet tuning should differ");
        System.out.println("[PASS] shader presets drive live performance/quality budgets");
    }

    private static void testParallelRoomProbeEquivalence() throws Exception {
        final ExecutorService pool=Executors.newFixedThreadPool(4);final java.util.Set<String> threads=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());
        ParallelWorkExecutor parallel=new ParallelWorkExecutor(){@Override public int workerCount(){return 4;}@Override public void forRange(int from,int to,final RangeTask task)throws Exception{int units=to-from,parts=Math.min(4,units),base=units/parts,extra=units%parts,pos=from;final CountDownLatch latch=new CountDownLatch(parts);final java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<Throwable>();for(int i=0;i<parts;i++){final int a=pos,b=a+base+(i<extra?1:0);pos=b;pool.submit(new Runnable(){@Override public void run(){threads.add(Thread.currentThread().getName());try{task.run(a,b);}catch(Throwable t){error.compareAndSet(null,t);}finally{latch.countDown();}}});}if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("parallel room probe timeout");Throwable t=error.get();if(t!=null)throw new RuntimeException(t);}};
        try{VoxelTestScene room=VoxelTestScene.builder().boxShell(-10,0,-10,10,8,10,AcousticMaterials.STONE).build();LegacyAcousticEvaluator e=new LegacyAcousticEvaluator();Vec3 listener=new Vec3(0,2,0);LegacyRoomEstimate a=e.estimateRoom(room,listener,32,dev.acoustic.core.passes.LegacyEffectTuning.DEFAULT,384);LegacyRoomEstimate b=e.estimateRoomParallel(room,listener,32,dev.acoustic.core.passes.LegacyEffectTuning.DEFAULT,384,parallel);if(Math.abs(a.meanFreePathMeters()-b.meanFreePathMeters())>1e-9||Math.abs(a.decayTimeSeconds()-b.decayTimeSeconds())>1e-7||Math.abs(a.openness()-b.openness())>1e-7)throw new AssertionError("parallel room probe differs from scalar");if(threads.size()<2)throw new AssertionError("room probe did not execute on multiple worker threads: "+threads);}
        finally{pool.shutdownNow();}
        System.out.println("[PASS] live room-ray analysis deterministic true multithreading");
    }

    private static void testUltraProfileUsesParallelFdtd() throws Exception {
        FdtdBackendRegistry.clearForTests();
        LoadedShaderPack p=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));
        VoxelTestScene room=VoxelTestScene.builder().boxShell(-6,0,-6,6,6,6,AcousticMaterials.STONE).build();
        AcousticRuntimeSession s=new AcousticRuntimeSession(p,"ULTRA",2);try{
            AcousticRuntimeSession.FrameResult result=s.process(room,new Vec3(2,2,0),new Vec3(0,2,0));
            if(!result.response().hasWaveField())throw new AssertionError("ULTRA wave field missing");
            if(!result.response().wave().backendId().startsWith("fdtd/cpu-parallel"))throw new AssertionError("ULTRA did not select parallel FDTD: "+result.response().wave().backendId());
            if(!result.response().wave().hasTimeDomainResponse())throw new AssertionError("FDTD listener impulse missing");
        }finally{s.close();FdtdBackendRegistry.clearForTests();}
        System.out.println("[PASS] ULTRA profile true multicore FDTD backend");
    }

    private static void testParallelFdtdEquivalentToScalar() throws Exception {
        FdtdBackendRegistry.clearForTests();
        LoadedShaderPack p=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));
        VoxelTestScene room=VoxelTestScene.builder().boxShell(-5,0,-5,5,5,5,AcousticMaterials.STONE).build();
        AcousticRuntimeSession scalar=new AcousticRuntimeSession(p,"ULTRA",1);AcousticRuntimeSession parallel=new AcousticRuntimeSession(p,"ULTRA",4);
        try{
            float[] a=scalar.process(room,new Vec3(2,2,0),new Vec3(0,2,0)).response().wave().listenerImpulse();
            float[] b=parallel.process(room,new Vec3(2,2,0),new Vec3(0,2,0)).response().wave().listenerImpulse();
            if(a.length!=b.length)throw new AssertionError("FDTD response size mismatch");
            double max=0;for(int i=0;i<a.length;i++)max=Math.max(max,Math.abs(a[i]-b[i]));
            if(max>1.0e-6)throw new AssertionError("parallel FDTD diverged from scalar, max="+max);
        }finally{scalar.close();parallel.close();FdtdBackendRegistry.clearForTests();}
        System.out.println("[PASS] scalar/parallel FDTD numerical equivalence");
    }

    private static void testHeterogeneousFdtdReducesToHomogeneous() {
        int nx=7,ny=5,nz=5,cells=nx*ny*nz,source=1+nx*(2+ny*2),listener=5+nx*(2+ny*2);
        int[] solid=new int[cells];float[] reflection=new float[cells];java.util.Arrays.fill(reflection,1f);
        FdtdProblem homogeneous=new FdtdProblem(nx,ny,nz,96,source,listener,0.11f,0.001f,solid,reflection);
        float[] lambda=new float[cells],damping=new float[cells],density=new float[cells];
        java.util.Arrays.fill(lambda,0.11f);java.util.Arrays.fill(damping,0.001f);java.util.Arrays.fill(density,1f);
        FdtdProblem heterogeneous=new FdtdProblem(nx,ny,nz,96,source,listener,0.11f,0.001f,solid,reflection,lambda,damping,density);
        float[] a=dev.acoustic.core.wave.FdtdWavePass.solveCpuScalar(homogeneous),b=dev.acoustic.core.wave.FdtdWavePass.solveCpuScalar(heterogeneous);
        double max=0;for(int i=0;i<a.length;i++)max=Math.max(max,Math.abs(a[i]-b[i]));
        if(max>1.0e-6)throw new AssertionError("equal-density heterogeneous FDTD must reduce to legacy homogeneous stencil, max="+max);
        for(int z=0;z<nz;z++)for(int y=0;y<ny;y++)for(int x=3;x<nx;x++){int i=x+nx*(y+ny*z);lambda[i]=0.19f;density[i]=998.2f;}
        FdtdProblem waterInterface=new FdtdProblem(nx,ny,nz,96,source,listener,0.11f,0.001f,solid,reflection,lambda,damping,density);
        float[] c=dev.acoustic.core.wave.FdtdWavePass.solveCpuScalar(waterInterface);
        boolean nonZero=false;for(float v:c){if(!Float.isFinite(v))throw new AssertionError("heterogeneous air/water FDTD produced non-finite pressure");if(Math.abs(v)>1e-8)nonZero=true;}
        if(!nonZero)throw new AssertionError("heterogeneous air/water FDTD lost all propagation");
        System.out.println("[PASS] heterogeneous variable-density FDTD + homogeneous-limit equivalence");
    }

    private static void testPartialFluidFdtdSampling() throws Exception {
        FdtdBackendRegistry.clearForTests();
        final java.util.concurrent.atomic.AtomicReference<FdtdProblem> captured=new java.util.concurrent.atomic.AtomicReference<FdtdProblem>();
        FdtdBackendRegistry.register(new FdtdExternalBackend(){
            @Override public String id(){return "partial-capture";}
            @Override public String description(){return "captures prepared partial-fluid FDTD problem";}
            @Override public boolean available(){return true;}
            @Override public boolean supports(FdtdProblem p){return true;}
            @Override public float[] solve(FdtdProblem p){captured.set(p);return new float[p.maxSteps()];}
        });
        VoxelTestScene scene=VoxelTestScene.builder().partialMedium(0,0,0,dev.acoustic.api.environment.AcousticMedia.WATER,0.5).build();
        MapPassContext context=new MapPassContext();
        context.put(StandardResources.SCENE,scene);
        context.put(StandardResources.SOURCE_POSITION,new Vec3(.5,.25,.5));
        context.put(StandardResources.LISTENER_POSITION,new Vec3(.5,.75,.5));
        try {
            new dev.acoustic.core.wave.FdtdWavePass(2.0,4,32,0.45,"partial-capture").execute(context);
            FdtdProblem p=captured.get();if(p==null)throw new AssertionError("partial-fluid FDTD problem was not sent to capture backend");
            float[] density=p.densityField();if(density==null)throw new AssertionError("partial fluid must produce heterogeneous FDTD fields");
            boolean air=false,water=false;for(float d:density){if(d<10f)air=true;if(d>900f)water=true;}
            if(!air||!water)throw new AssertionError("subvoxel FDTD sampling must preserve both air and water cells in one partial fluid voxel");
        } finally { FdtdBackendRegistry.clearForTests(); }
        System.out.println("[PASS] partial-fluid subvoxel FDTD medium sampling");
    }

    private static void testExternalFdtdBackendSelectionAndFallback() throws Exception {
        FdtdBackendRegistry.clearForTests();
        final AtomicInteger calls=new AtomicInteger();
        FdtdBackendRegistry.register(new FdtdExternalBackend(){
            @Override public String id(){return "fake-gpu";}@Override public String description(){return "test external backend";}@Override public boolean available(){return true;}@Override public boolean supports(FdtdProblem p){return true;}
            @Override public float[] solve(FdtdProblem p){calls.incrementAndGet();float[] out=new float[p.maxSteps()];if(out.length>2)out[2]=0.25f;return out;}
        });
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));VoxelTestScene scene=VoxelTestScene.builder().boxShell(-5,0,-5,5,5,5,AcousticMaterials.STONE).build();
        AcousticRuntimeSession session=new AcousticRuntimeSession(pack,"ULTRA",2);try{
            String id=session.process(scene,new Vec3(2,2,0),new Vec3(0,2,0)).response().wave().backendId();
            if(!"fdtd/fake-gpu".equals(id)||calls.get()!=1)throw new AssertionError("AUTO did not select external backend: "+id);
        }finally{session.close();FdtdBackendRegistry.clearForTests();}
        FdtdBackendRegistry.register(new FdtdExternalBackend(){
            @Override public String id(){return "broken";}@Override public String description(){return "broken";}@Override public boolean available(){return true;}@Override public boolean supports(FdtdProblem p){return true;}@Override public float[] solve(FdtdProblem p)throws Exception{throw new Exception("expected");}
        });
        session=new AcousticRuntimeSession(pack,"ULTRA",2);try{
            String id=session.process(scene,new Vec3(2,2,0),new Vec3(0,2,0)).response().wave().backendId();
            if(!id.startsWith("fdtd/cpu-parallel"))throw new AssertionError("failed external backend did not fall back to CPU: "+id);
        }finally{session.close();FdtdBackendRegistry.clearForTests();}
        System.out.println("[PASS] optional GPU backend selection + safe CPU fallback");
    }

    private static void testExternalGeometricBackendSelectionAndFallback() throws Exception {
        GeometricBackendRegistry.clearForTests();final AtomicInteger calls=new AtomicInteger();final VoxelTestScene scene=VoxelTestScene.builder().boxShell(-5,0,-5,5,5,5,AcousticMaterials.STONE).build();
        GeometricBackendRegistry.register(new GeometricExternalBackend(){
            @Override public String id(){return "fake-ray";}@Override public String description(){return "fake ray accelerator";}@Override public boolean available(){return true;}@Override public boolean supports(dev.acoustic.api.scene.AcousticScene s,int r,int b,double d,double e){return true;}
            @Override public ReflectionField trace(dev.acoustic.api.scene.AcousticScene s,Vec3 listener,int rays,int bounces,double distance,double minEnergy){calls.incrementAndGet();return new ReflectionField(rays,Collections.singletonList(new ReflectionSample(2.0,1,new Vec3(1,0,0),new float[]{.5f,.5f,.5f,.5f,.5f,.5f,.5f,.5f})));}
        });
        ParallelPipelineExecutor executor=new ParallelPipelineExecutor(2);try{MapPassContext c=new MapPassContext();c.put(StandardResources.SCENE,scene);c.put(StandardResources.LISTENER_POSITION,new Vec3(0,2,0));dev.acoustic.core.pipeline.ExecutionReport report=executor.executeProfiled(new DefaultPipeline(Collections.<Pass>singletonList(new EnvironmentRayPass(64,2,10,.0001,"AUTO"))),c);if(calls.get()!=1)throw new AssertionError("geometric accelerator was not selected");if(!report.timing("standard.environment_rays").threadName().contains("accelerated[fake-ray]"))throw new AssertionError("accelerated pass was not profiled as device work: "+report.timing("standard.environment_rays").threadName());}
        finally{executor.close();GeometricBackendRegistry.clearForTests();}
        GeometricBackendRegistry.register(new GeometricExternalBackend(){@Override public String id(){return "broken-ray";}@Override public String description(){return "broken";}@Override public boolean available(){return true;}@Override public boolean supports(dev.acoustic.api.scene.AcousticScene s,int r,int b,double d,double e){return true;}@Override public ReflectionField trace(dev.acoustic.api.scene.AcousticScene s,Vec3 l,int r,int b,double d,double e)throws Exception{throw new Exception("expected");}});
        executor=new ParallelPipelineExecutor(2);try{MapPassContext c=new MapPassContext();c.put(StandardResources.SCENE,scene);c.put(StandardResources.LISTENER_POSITION,new Vec3(0,2,0));dev.acoustic.core.pipeline.ExecutionReport report=executor.executeProfiled(new DefaultPipeline(Collections.<Pass>singletonList(new EnvironmentRayPass(64,2,10,.0001,"AUTO"))),c);if(!report.timing("standard.environment_rays").threadName().startsWith("parallel["))throw new AssertionError("failed ray accelerator did not fall back to CPU: "+report.timing("standard.environment_rays").threadName());if(c.require(StandardResources.REFLECTION_FIELD).raysTraced()!=64)throw new AssertionError("CPU ray fallback missing");}
        finally{executor.close();GeometricBackendRegistry.clearForTests();}
        GeometricBackendRegistry.register(new GeometricExternalBackend(){@Override public String id(){return "null-ray";}@Override public String description(){return "null requests CPU fallback";}@Override public boolean available(){return true;}@Override public boolean supports(dev.acoustic.api.scene.AcousticScene s,int r,int b,double d,double e){return true;}@Override public ReflectionField trace(dev.acoustic.api.scene.AcousticScene s,Vec3 l,int r,int b,double d,double e){return null;}});
        executor=new ParallelPipelineExecutor(2);try{MapPassContext c=new MapPassContext();c.put(StandardResources.SCENE,scene);c.put(StandardResources.LISTENER_POSITION,new Vec3(0,2,0));dev.acoustic.core.pipeline.ExecutionReport report=executor.executeProfiled(new DefaultPipeline(Collections.<Pass>singletonList(new EnvironmentRayPass(64,2,10,.0001,"AUTO"))),c);if(!report.timing("standard.environment_rays").threadName().startsWith("parallel["))throw new AssertionError("null ray accelerator result did not request CPU fallback: "+report.timing("standard.environment_rays").threadName());if(c.require(StandardResources.REFLECTION_FIELD).raysTraced()!=64)throw new AssertionError("CPU ray fallback missing after null accelerator result");}
        finally{executor.close();GeometricBackendRegistry.clearForTests();}
        System.out.println("[PASS] optional geometric accelerator selection + safe CPU fallback");
    }

    private static void testAutoBackendPreferenceVsExplicitSelection() throws Exception {
        GeometricBackendRegistry.clearForTests();final AtomicInteger calls=new AtomicInteger();final VoxelTestScene scene=VoxelTestScene.builder().boxShell(-4,0,-4,4,4,4,AcousticMaterials.STONE).build();
        GeometricBackendRegistry.register(new GeometricExternalBackend(){
            @Override public String id(){return "deferred-gpu";}@Override public String description(){return "supported but not worthwhile for AUTO";}@Override public boolean available(){return true;}@Override public boolean supports(dev.acoustic.api.scene.AcousticScene s,int r,int b,double d,double e){return true;}@Override public boolean preferredForAuto(dev.acoustic.api.scene.AcousticScene s,int r,int b,double d,double e){return false;}
            @Override public ReflectionField trace(dev.acoustic.api.scene.AcousticScene s,Vec3 l,int r,int b,double d,double e){calls.incrementAndGet();return new ReflectionField(r,Collections.<ReflectionSample>emptyList());}
        });
        ParallelPipelineExecutor executor=new ParallelPipelineExecutor(2);try{MapPassContext c=new MapPassContext();c.put(StandardResources.SCENE,scene);c.put(StandardResources.LISTENER_POSITION,new Vec3(0,2,0));executor.execute(new DefaultPipeline(Collections.<Pass>singletonList(new EnvironmentRayPass(32,1,8,.001,"AUTO"))),c);if(calls.get()!=0)throw new AssertionError("AUTO ignored backend preference policy");}
        finally{executor.close();}
        executor=new ParallelPipelineExecutor(2);try{MapPassContext c=new MapPassContext();c.put(StandardResources.SCENE,scene);c.put(StandardResources.LISTENER_POSITION,new Vec3(0,2,0));executor.execute(new DefaultPipeline(Collections.<Pass>singletonList(new EnvironmentRayPass(32,1,8,.001,"deferred-gpu"))),c);if(calls.get()!=1)throw new AssertionError("explicit accelerator selection should override AUTO preference");}
        finally{executor.close();GeometricBackendRegistry.clearForTests();}

        FdtdBackendRegistry.clearForTests();
        final FdtdExternalBackend deferredFdtd=new FdtdExternalBackend(){
            @Override public String id(){return "deferred-fdtd";}@Override public String description(){return "supported but not worthwhile for AUTO";}@Override public boolean available(){return true;}@Override public boolean supports(FdtdProblem p){return true;}@Override public boolean preferredForAuto(FdtdProblem p){return false;}@Override public float[] solve(FdtdProblem p){throw new AssertionError("AUTO must not invoke non-preferred FDTD backend");}
        };
        FdtdBackendRegistry.register(deferredFdtd);
        FdtdProblem tiny=new FdtdProblem(5,5,5,32,62,63,0.1f,0.001f,new int[125],new float[125]);
        if(FdtdBackendRegistry.firstSupported(tiny)!=deferredFdtd)throw new AssertionError("explicit FDTD backend lookup lost a supported backend");
        if(FdtdBackendRegistry.firstPreferred(tiny)!=null)throw new AssertionError("AUTO ignored FDTD preferredForAuto=false");
        FdtdBackendRegistry.clearForTests();
        System.out.println("[PASS] AUTO accelerator policy avoids tiny device workloads while explicit selection remains authoritative");
    }

    private static void testBackendPriorityCudaBeforeOpenCl() {
        FdtdBackendRegistry.clearForTests();
        FdtdExternalBackend openclFdtd=new FdtdExternalBackend(){
            @Override public String id(){return "opencl";} @Override public String description(){return "fake OpenCL";} @Override public int autoPriority(){return 100;} @Override public boolean available(){return true;} @Override public boolean supports(FdtdProblem p){return true;} @Override public float[] solve(FdtdProblem p){return new float[p.maxSteps()];}
        };
        FdtdExternalBackend cudaFdtd=new FdtdExternalBackend(){
            @Override public String id(){return "cuda";} @Override public String description(){return "fake CUDA";} @Override public int autoPriority(){return 200;} @Override public boolean available(){return true;} @Override public boolean supports(FdtdProblem p){return true;} @Override public float[] solve(FdtdProblem p){return new float[p.maxSteps()];}
        };
        FdtdBackendRegistry.register(openclFdtd);FdtdBackendRegistry.register(cudaFdtd);
        FdtdProblem p=new FdtdProblem(5,5,5,32,62,63,0.1f,0.001f,new int[125],new float[125]);
        if(FdtdBackendRegistry.firstSupported(p)!=cudaFdtd||FdtdBackendRegistry.firstPreferred(p)!=cudaFdtd)throw new AssertionError("FDTD AUTO priority must prefer CUDA over OpenCL");
        if(FdtdBackendRegistry.find("opencl")!=openclFdtd)throw new AssertionError("explicit OpenCL FDTD selection was lost");
        FdtdBackendRegistry.clearForTests();

        GeometricBackendRegistry.clearForTests();
        GeometricExternalBackend openclRay=new GeometricExternalBackend(){
            @Override public String id(){return "opencl";} @Override public String description(){return "fake OpenCL";} @Override public int autoPriority(){return 100;} @Override public boolean available(){return true;} @Override public boolean supports(dev.acoustic.api.scene.AcousticScene s,int r,int b,double d,double e){return true;} @Override public ReflectionField trace(dev.acoustic.api.scene.AcousticScene s,Vec3 l,int r,int b,double d,double e){return new ReflectionField(r,Collections.<ReflectionSample>emptyList());}
        };
        GeometricExternalBackend cudaRay=new GeometricExternalBackend(){
            @Override public String id(){return "cuda";} @Override public String description(){return "fake CUDA";} @Override public int autoPriority(){return 200;} @Override public boolean available(){return true;} @Override public boolean supports(dev.acoustic.api.scene.AcousticScene s,int r,int b,double d,double e){return true;} @Override public ReflectionField trace(dev.acoustic.api.scene.AcousticScene s,Vec3 l,int r,int b,double d,double e){return new ReflectionField(r,Collections.<ReflectionSample>emptyList());}
        };
        GeometricBackendRegistry.register(openclRay);GeometricBackendRegistry.register(cudaRay);
        VoxelTestScene scene=VoxelTestScene.builder().boxShell(-2,0,-2,2,3,2,AcousticMaterials.STONE).build();
        if(GeometricBackendRegistry.firstSupported(scene,64,2,10,.001)!=cudaRay||GeometricBackendRegistry.firstPreferred(scene,64,2,10,.001)!=cudaRay)throw new AssertionError("ray AUTO priority must prefer CUDA over OpenCL");
        if(GeometricBackendRegistry.find("opencl")!=openclRay)throw new AssertionError("explicit OpenCL ray selection was lost");
        GeometricBackendRegistry.clearForTests();
        System.out.println("[PASS] explicit accelerator priority CUDA > OpenCL while explicit OpenCL remains selectable");
    }

    private static void testAcceleratorOverlapsIndependentCpuWork() throws Exception {
        final CountDownLatch acceleratorStarted=new CountDownLatch(1),cpuStarted=new CountDownLatch(1);
        Pass accelerator=new AcceleratedPass(){
            @Override public String id(){return "test.accelerator";}@Override public Set<dev.acoustic.api.pipeline.ResourceKey<?>> reads(){return Collections.emptySet();}@Override public Set<dev.acoustic.api.pipeline.ResourceKey<?>> writes(){return Collections.emptySet();}@Override public void execute(PassContext c){throw new AssertionError("accelerator unexpectedly fell back to CPU");}
            @Override public String tryExecuteAccelerated(PassContext c)throws Exception{acceleratorStarted.countDown();if(!cpuStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("independent CPU work did not overlap accelerator");return "fake-device";}
        };
        Pass cpu=new Pass(){
            @Override public String id(){return "test.cpu";}@Override public Set<dev.acoustic.api.pipeline.ResourceKey<?>> reads(){return Collections.emptySet();}@Override public Set<dev.acoustic.api.pipeline.ResourceKey<?>> writes(){return Collections.emptySet();}@Override public void execute(PassContext c)throws Exception{cpuStarted.countDown();if(!acceleratorStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("accelerator did not start concurrently");}
        };
        ParallelPipelineExecutor executor=new ParallelPipelineExecutor(2);try{dev.acoustic.core.pipeline.ExecutionReport report=executor.executeProfiled(new DefaultPipeline(Arrays.asList(accelerator,cpu)),new MapPassContext());if(!report.timing("test.accelerator").threadName().contains("accelerated[fake-device]"))throw new AssertionError("accelerator timing label missing");}
        finally{executor.close();}
        System.out.println("[PASS] independent GPU/accelerator host work overlaps bounded CPU DAG work");
    }

    private static void testRollingSceneCaptureReuseAndSingleRead() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));final AtomicInteger reads=new AtomicInteger();
        LegacyWorldAccess world=new LegacyWorldAccess(){
            @Override public LegacyBlockSample sample(int x,int y,int z){reads.incrementAndGet();boolean solid=y==0;return new LegacyBlockSample(solid?"minecraft:stone":"minecraft:air",solid?"rock":"air",solid?"stone":"",Collections.<String>emptySet(),solid);}
            @Override public String registryId(int x,int y,int z){throw new AssertionError("slow registryId path used");}@Override public String materialName(int x,int y,int z){throw new AssertionError("slow material path used");}@Override public String soundTypeName(int x,int y,int z){throw new AssertionError("slow sound path used");}@Override public Set<String> oreDictionaryNames(int x,int y,int z){throw new AssertionError("slow ore path used");}@Override public boolean solid(int x,int y,int z){throw new AssertionError("slow solid path used");}@Override public long revision(){return 1L;}
        };
        LegacySceneCapture capture=new LegacySceneCapture(MaterialResolverCompiler.compile(pack));
        capture.captureRolling(world,-5,0,-5,11,5,11,0,2,0,2,true);int first=reads.get();
        reads.set(0);capture.captureRolling(world,-4,0,-5,11,5,11,1,2,0,2,false);int second=reads.get();
        if(first!=11*5*11)throw new AssertionError("full capture did not use exactly one sample per voxel: "+first);
        if(second>=first/2)throw new AssertionError("rolling capture failed to reuse overlap: first="+first+" second="+second);
        if(capture.lastSampledVoxels()!=second||capture.lastReusedVoxels()<=0)throw new AssertionError("rolling capture counters invalid");
        System.out.println("[PASS] overlap-reused scene capture + one world-state read per sampled voxel");
    }

    private static void testProgressiveInitialCaptureIsCenterOutAndBounded() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));final AtomicInteger reads=new AtomicInteger();
        LegacyWorldAccess world=new LegacyWorldAccess(){
            @Override public LegacyBlockSample sample(int x,int y,int z){reads.incrementAndGet();boolean solid=(x==2&&z==2)||(x==0&&z==0);return new LegacyBlockSample(solid?"minecraft:stone":"minecraft:air",solid?"rock":"air",solid?"stone":"",Collections.<String>emptySet(),solid);}
            @Override public String registryId(int x,int y,int z){throw new AssertionError("slow registryId path used");}@Override public String materialName(int x,int y,int z){throw new AssertionError("slow material path used");}@Override public String soundTypeName(int x,int y,int z){throw new AssertionError("slow sound path used");}@Override public Set<String> oreDictionaryNames(int x,int y,int z){throw new AssertionError("slow ore path used");}@Override public boolean solid(int x,int y,int z){throw new AssertionError("slow solid path used");}@Override public long revision(){return 9L;}
        };
        LegacySceneCapture capture=new LegacySceneCapture(MaterialResolverCompiler.compile(pack));
        dev.acoustic.core.scene.ImmutableVoxelSnapshot first=capture.captureRollingProgressive(world,0,0,0,5,1,5,2,0,2,0,true,0.0,false,0);
        if(reads.get()!=1)throw new AssertionError("zero-budget bootstrap must still make exactly one unit of progress: "+reads.get());
        if(!first.voxelAt(2,0,2).solid())throw new AssertionError("progressive bootstrap did not prioritize listener-center geometry");
        if(first.voxelAt(0,0,0).solid())throw new AssertionError("far bootstrap geometry should not be sampled before the center");
        if(!capture.bootstrapActive()||capture.bootstrapProgress()<=0.0||capture.bootstrapProgress()>=1.0)throw new AssertionError("bootstrap progress state invalid: "+capture.bootstrapProgress());
        dev.acoustic.core.scene.ImmutableVoxelSnapshot complete=first;
        for(int step=1;step<25;step++){
            reads.set(0);double before=capture.bootstrapProgress();
            complete=capture.captureRollingProgressive(world,0,0,0,5,1,5,2,0,2,0,false,0.0,false,0);
            if(reads.get()!=1)throw new AssertionError("zero-budget bootstrap must sample exactly one new voxel per call at step "+step+": "+reads.get());
            if(step<24&&(!capture.bootstrapActive()||capture.bootstrapProgress()<=before))throw new AssertionError("zero-budget bootstrap stopped making progress at step "+step+": "+capture.bootstrapProgress());
        }
        if(capture.bootstrapActive()||capture.bootstrapProgress()!=1.0)throw new AssertionError("bootstrap did not finish after exactly 25 zero-budget samples");
        if(!complete.voxelAt(0,0,0).solid())throw new AssertionError("far geometry missing after deterministic bootstrap completion");
        System.out.println("[PASS] center-out wall-clock-bounded progressive initial scene capture + exact zero-budget progress");
    }

    private static void testBudgetedSceneRefreshAvoidsRevisionChurn() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));final AtomicInteger reads=new AtomicInteger();final AtomicInteger revision=new AtomicInteger(1);final boolean[] changed={false};
        LegacyWorldAccess world=new LegacyWorldAccess(){
            @Override public LegacyBlockSample sample(int x,int y,int z){reads.incrementAndGet();boolean solid=changed[0]&&x==0&&y==0&&z==0;return new LegacyBlockSample(solid?"minecraft:stone":"minecraft:air",solid?"rock":"air",solid?"stone":"",Collections.<String>emptySet(),solid);}
            @Override public String registryId(int x,int y,int z){throw new AssertionError("slow registryId path used");}@Override public String materialName(int x,int y,int z){throw new AssertionError("slow material path used");}@Override public String soundTypeName(int x,int y,int z){throw new AssertionError("slow sound path used");}@Override public Set<String> oreDictionaryNames(int x,int y,int z){throw new AssertionError("slow ore path used");}@Override public boolean solid(int x,int y,int z){throw new AssertionError("slow solid path used");}
            @Override public long revision(){return revision.get();}
        };
        LegacySceneCapture capture=new LegacySceneCapture(MaterialResolverCompiler.compile(pack));
        dev.acoustic.core.scene.ImmutableVoxelSnapshot initial=capture.captureRolling(world,0,0,0,10,1,10,0,0,0,0,true);if(reads.get()!=100)throw new AssertionError("initial budget test capture size");
        reads.set(0);revision.set(2);dev.acoustic.core.scene.ImmutableVoxelSnapshot unchanged=capture.captureRollingBudgeted(world,0,0,0,10,1,10,50,0,50,0,false,true,17);
        if(reads.get()>17||capture.lastSweepSampledVoxels()>17)throw new AssertionError("budgeted sweep exceeded sample budget: reads="+reads.get());
        if(capture.lastChangedVoxels()!=0||unchanged.revision()!=initial.revision())throw new AssertionError("unchanged acoustic sweep churned scene revision");
        changed[0]=true;revision.set(3);reads.set(0);dev.acoustic.core.scene.ImmutableVoxelSnapshot updated=capture.captureRollingBudgeted(world,0,0,0,10,1,10,50,0,50,0,false,true,17);
        if(capture.lastChangedVoxels()<1||updated.revision()!=3L)throw new AssertionError("changed voxel was not published by budgeted sweep");
        System.out.println("[PASS] time-sliced world validation preserves acoustic revision until content changes");
    }

    private static void testMaximumPresetQualityEnvelope() throws Exception {
        LoadedShaderPack p=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));
        dev.acoustic.core.runtime.ResolvedProfile high=dev.acoustic.core.runtime.ResolvedProfile.from(p.options(),"HIGH",Collections.<String,String>emptyMap());
        dev.acoustic.core.runtime.ResolvedProfile max=dev.acoustic.core.runtime.ResolvedProfile.from(p.options(),"MAXIMUM",Collections.<String,String>emptyMap());
        if(max.getInt("RAYS",0)<=high.getInt("RAYS",0)||max.getInt("BOUNCES",0)<=high.getInt("BOUNCES",0)||max.getInt("FDTD_STEPS",0)<=high.getInt("FDTD_STEPS",0)||max.getDouble("RIR_SECONDS",0)<=high.getDouble("RIR_SECONDS",0))throw new AssertionError("MAXIMUM is not a strict quality envelope over HIGH");
        if(!"FDTD".equals(max.get("WAVE","")))throw new AssertionError("MAXIMUM must use FDTD");
        System.out.println("[PASS] MAXIMUM preset prioritizes quality/realism envelope");
    }

    private static void testCpuThreadOverrideIsBounded() throws Exception {
        int available=Math.max(1,Runtime.getRuntime().availableProcessors());Map<String,String> overrides=new LinkedHashMap<String,String>();overrides.put("CPU_THREADS","32");
        LegacyRuntimeConfig cfg=new LegacyRuntimeConfig(Collections.singletonList("reference-pack"),"HIGH",18,10,10,24,true,false,overrides);
        LegacyPerformanceTuning t=LegacyShaderPackRuntime.load(Paths.get("examples"),cfg).performanceTuning();
        if(t.workers()<1||t.workers()>available)throw new AssertionError("CPU worker count escaped processor bound: "+t.workers()+" available="+available);if(t.liveSourceLimit()<1||t.liveSourceLimit()>32||t.sourceMoveThreshold()<0.05)throw new AssertionError("live source scheduling controls invalid");
        System.out.println("[PASS] configurable CPU worker pool and live-source scheduling are bounded");
    }
    private static void testLegacyLivePipelineStopsAtHybrid() throws Exception {
        LegacyShaderPackRuntime runtime=LegacyShaderPackRuntime.load(Paths.get("examples"),new LegacyRuntimeConfig("reference-pack","HIGH",18,10,10,24,true,false));
        java.util.List<Pass> passes=runtime.livePipeline().passes();if(passes.isEmpty()||!"standard.hybrid".equals(passes.get(passes.size()-1).id()))throw new AssertionError("legacy live pipeline must terminate at hybrid response");
        for(Pass pass:passes)if("standard.impulse_response".equals(pass.id()))throw new AssertionError("legacy EFX live pipeline should not allocate full RIR");
        if(runtime.listenerInvariantPipeline().passes().size()!=1||!"standard.environment_rays".equals(runtime.listenerInvariantPipeline().passes().get(0).id()))throw new AssertionError("listener-invariant reflection stage was not isolated");
        boolean hasRir=false;for(Pass pass:runtime.sourcePipeline().passes()){if("standard.environment_rays".equals(pass.id()))throw new AssertionError("source pipeline redundantly contains shared environment rays");if("standard.impulse_response".equals(pass.id()))hasRir=true;}
        if(!hasRir)throw new AssertionError("full source pipeline must retain impulse response for optional software wet output");
        System.out.println("[PASS] legacy fast EFX DAG stops at hybrid while full source DAG retains RIR + shared listener rays");
    }

    private static void testFullHybridLegacyProjection() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));VoxelTestScene room=VoxelTestScene.builder().boxShell(-6,0,-6,6,6,6,AcousticMaterials.STONE).build();
        AcousticRuntimeSession session=new AcousticRuntimeSession(pack,"HIGH",2);try{AcousticRuntimeSession.FrameResult frame=session.process(room,new Vec3(8,2,0),new Vec3(0,2,0));LegacyEffectParameters effect=new HybridLegacyProjector().project(frame.response());if(effect.directGain()>=0.75f)throw new AssertionError("full hybrid projector failed to preserve blocked-path attenuation: "+effect.directGain());if(effect.sendGain()<0f||effect.sendGain()>1f)throw new AssertionError("full hybrid wet projection escaped unit range");}
        finally{session.close();}
        System.out.println("[PASS] full hybrid response projects safely to legacy EFX controls");
    }

    private static void testStateSpecificMaterialRules(){
        MaterialRule generic=new MaterialRule(10,MaterialRule.MatchKind.REGISTRY_ID,"mod:block",AcousticMaterials.STONE);
        MaterialRule state=new MaterialRule(20,MaterialRule.MatchKind.STATE_ID,"mod:block#meta=7",AcousticMaterials.METAL);
        MaterialResolver r=new MaterialResolver(Arrays.asList(generic,state),AcousticMaterials.STONE,AcousticMaterials.AIR);
        MaterialDescriptor d=new MaterialDescriptor("mod:block","mod:block#meta=7",Collections.<String>emptySet(),Collections.<String>emptySet(),true);
        if(r.resolve(d)!=AcousticMaterials.METAL)throw new AssertionError("state-specific material rule did not win");
        System.out.println("[PASS] indexed state-specific acoustic material rules");
    }

    private static void testUnknownBlockMaterialInference(){
        MaterialInferenceEngine e=new MaterialInferenceEngine();
        InferredMaterial steel=e.infer(new MaterialFacts("mod:reinforced_casing","mod:reinforced_casing#meta=0","iron","metal",Collections.singleton("blockSteel"),true,true,true,false,5f,30f));
        if(!"metal".equals(steel.family())||steel.confidence()<0.70f||steel.material().reflection(3)<0.80f)throw new AssertionError("steel inference failed: "+steel.family()+" conf="+steel.confidence());
        InferredMaterial foam=e.infer(new MaterialFacts("mod:acoustic_foam","mod:acoustic_foam#meta=0","cloth","cloth",Collections.<String>emptySet(),true,true,false,false,0.2f,0.5f));
        if(!"fabric".equals(foam.family())||foam.material().absorption(6)<0.6f)throw new AssertionError("porous/fabric inference failed: "+foam.family());
        InferredMaterial air=e.infer(new MaterialFacts("mod:ghost","mod:ghost#meta=0","air","",Collections.<String>emptySet(),false,false,false,false,0f,0f));
        if(!"air".equals(air.family()))throw new AssertionError("non-solid unknown should conservatively infer air");
        System.out.println("[PASS] cached unknown/modded block material inference model");
    }

    private static void testGeneratedMaterialPackRoundTrip() throws Exception {
        MaterialFacts f=new MaterialFacts("mod:copper_block","mod:copper_block#meta=0","iron","metal",Collections.singleton("blockCopper"),true,true,true,false,4f,20f);
        InferredMaterial im=new MaterialInferenceEngine().infer(f);java.util.List<GeneratedMaterialPackWriter.Row> rows=Collections.singletonList(new GeneratedMaterialPackWriter.Row(f,im));
        String json=new GeneratedMaterialPackWriter().write(rows,"abc123");MaterialPack p=MaterialPack.parse(json);MaterialResolver r=new MaterialResolver(p.rules(),AcousticMaterials.STONE,AcousticMaterials.AIR);
        dev.acoustic.api.material.AcousticMaterial m=r.resolve(new MaterialDescriptor("mod:copper_block","mod:copper_block#meta=0",Collections.<String>emptySet(),Collections.<String>emptySet(),true));
        if(!m.id().startsWith("generated:metal:"))throw new AssertionError("generated material JSON did not resolve exact state: "+m.id());
        System.out.println("[PASS] deterministic generated material database JSON round-trip");
    }

    private static void testResourcePackAcousticMaterialOverlay() throws Exception {
        Path root=Files.createTempDirectory("acoustic-material-resourcepack");Path dir=root.resolve("assets/example/acoustic_materials");Files.createDirectories(dir);
        String json="{\"materials\":{\"custom:soft\":{\"absorption\":[0.2,0.2,0.3,0.4,0.5,0.6,0.7,0.8],\"scattering\":0.4,\"transmission\":0.05}},\"rules\":[{\"priority\":12,\"kind\":\"REGISTRY_ID\",\"match\":\"mod:soft\",\"material\":\"custom:soft\"}]}";Files.write(dir.resolve("materials.json"),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.List<AcousticMaterialResourceLoader.Entry> entries=new AcousticMaterialResourceLoader().load(root);if(entries.size()!=1||entries.get(0).pack().rules().size()!=1)throw new AssertionError("resource-pack acoustic material overlay not discovered");
        java.util.List<Path> all=new java.util.ArrayList<Path>();Files.walk(root).forEach(all::add);Collections.reverse(all);for(Path x:all)Files.deleteIfExists(x);
        System.out.println("[PASS] ordinary resource-pack acoustic material overlay discovery");
    }

    private static void testResourcePackAndShaderMediumOverlay() throws Exception {
        String json="{\"media\":{\"test:oil\":{\"density_kg_m3\":850,\"speed_m_s\":1320,\"attenuation_db_per_km\":[0.2,0.3,0.5,0.8,1.2,2,4,8]}},\"rules\":[{\"priority\":50,\"kind\":\"REGISTRY_ID\",\"match\":\"mod:oil\",\"medium\":\"test:oil\"}]}";
        Path resource=Files.createTempDirectory("acoustic-medium-resourcepack"),dir=resource.resolve("assets/example/acoustic_media");Files.createDirectories(dir);Files.write(dir.resolve("media.json"),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.List<AcousticMediumResourceLoader.Entry> entries=new AcousticMediumResourceLoader().load(resource);if(entries.size()!=1||entries.get(0).pack().rules().size()!=1)throw new AssertionError("resource-pack acoustic medium overlay not discovered");MediumResolver mediumResolver=new MediumResolver(entries.get(0).pack().rules());dev.acoustic.api.environment.AcousticMedium oil=mediumResolver.resolve(new MaterialDescriptor("mod:oil","mod:oil#meta=0",Collections.<String>emptySet(),Collections.<String>emptySet(),false),dev.acoustic.api.environment.AcousticMedia.WATER);if(!"test:oil".equals(oil.id())||Math.abs(oil.speedOfSoundMetersPerSecond()-1320)>1e-9)throw new AssertionError("resource-pack medium did not resolve custom propagation properties");
        Path shader=Files.createTempDirectory("acoustic-medium-shaderpack");Files.write(shader.resolve("manifest.json"),"{\"format\":1,\"spec\":\"0.3\",\"id\":\"test:medium\",\"name\":\"Medium Test\",\"requires\":[],\"optional\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(shader.resolve("pipeline.json"),"{\"format\":1,\"passes\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));Path media=shader.resolve("media");Files.createDirectories(media);Files.write(media.resolve("oil.json"),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));LoadedShaderPack loaded=new ShaderPackLoader().loadDirectory(shader);if(loaded.mediumPacks().size()!=1||!loaded.mediumPacks().containsKey("oil.json"))throw new AssertionError("shader-pack media/ overlay not loaded");
        java.util.List<Path> all=new java.util.ArrayList<Path>();Files.walk(resource).forEach(all::add);Collections.reverse(all);for(Path x:all)Files.deleteIfExists(x);all.clear();Files.walk(shader).forEach(all::add);Collections.reverse(all);for(Path x:all)Files.deleteIfExists(x);
        System.out.println("[PASS] ordinary resource-pack + shader-pack acoustic medium overlay discovery");
    }

    private static void testSourceProfileParsingAndResolution(){
        SourceProfilePack pack=SourceProfilePack.parse(DefaultSourceProfiles.json());SourceProfileResolver resolver=new SourceProfileResolver(pack.rules(),AcousticSourceProfile.GENERIC);
        AcousticSourceProfile explosion=resolver.resolve("minecraft/sounds/entity/generic/explosion1.ogg"),arrow=resolver.resolve("minecraft/sounds/entity/arrow/shoot1.ogg"),music=resolver.resolve("minecraft/music/game.ogg");
        if(!"explosion".equals(explosion.category())||explosion.lateScale()<=1f||explosion.emission(0)<=explosion.emission(6))throw new AssertionError("explosion source profile invalid");
        if(!"projectile".equals(arrow.category())||arrow.dopplerScale()<=0f||arrow.movementSensitivity()<=1f)throw new AssertionError("projectile source profile invalid");
        if(!music.bypassAcoustics())throw new AssertionError("music must bypass world acoustics by default");
        System.out.println("[PASS] source-category profile parsing + explosion/projectile/nonspatial inference");
    }

    private static void testResourcePackSourceProfileOverlay() throws Exception {
        Path root=Files.createTempDirectory("acoustic-source-resourcepack");Path dir=root.resolve("assets/example/acoustic_sources");Files.createDirectories(dir);
        String json="{\"profiles\":{\"test:laser\":{\"category\":\"projectile\",\"emission\":[0.2,0.2,0.3,0.5,1,1.5,1.8,1.6],\"late_reverb\":0.2,\"doppler\":1.5}},\"rules\":[{\"priority\":500,\"kind\":\"GLOB\",\"match\":\"*laser*\",\"profile\":\"test:laser\"}]}";Files.write(dir.resolve("sources.json"),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.List<AcousticSourceResourceLoader.Entry> entries=new AcousticSourceResourceLoader().load(root);if(entries.size()!=1)throw new AssertionError("source-profile resource overlay not discovered");SourceProfileResolver resolver=new SourceProfileResolver(entries.get(0).pack().rules(),AcousticSourceProfile.GENERIC);if(!"test:laser".equals(resolver.resolve("mod/sounds/weapons/laser_fire.ogg").id()))throw new AssertionError("resource-pack source rule did not resolve");
        java.util.List<Path> all=new java.util.ArrayList<Path>();Files.walk(root).forEach(all::add);Collections.reverse(all);for(Path x:all)Files.deleteIfExists(x);System.out.println("[PASS] ordinary resource-pack acoustic source-profile overlay discovery");
    }

    private static void testSourceProfileAffectsLegacyProjection() throws Exception {
        SourceProfilePack sourcePack=SourceProfilePack.parse(DefaultSourceProfiles.json());SourceProfileResolver resolver=new SourceProfileResolver(sourcePack.rules(),AcousticSourceProfile.GENERIC);LoadedShaderPack shader=new ShaderPackLoader().loadDirectory(Paths.get("examples/reference-pack"));VoxelTestScene room=VoxelTestScene.builder().boxShell(-7,0,-7,7,7,7,AcousticMaterials.STONE).build();
        AcousticRuntimeSession session=new AcousticRuntimeSession(shader,"HIGH",2);try{AcousticRuntimeSession.FrameResult frame=session.process(room,new Vec3(4,2,0),new Vec3(0,2,0));HybridLegacyProjector projector=new HybridLegacyProjector();LegacyEffectParameters generic=projector.project(frame.response(),dev.acoustic.core.passes.LegacyEffectTuning.DEFAULT,AcousticSourceProfile.GENERIC),explosion=projector.project(frame.response(),dev.acoustic.core.passes.LegacyEffectTuning.DEFAULT,resolver.resolve("entity/explosion.ogg")),projectile=projector.project(frame.response(),dev.acoustic.core.passes.LegacyEffectTuning.DEFAULT,resolver.resolve("entity/arrow/fly.ogg"));if(explosion.sendGain()<=generic.sendGain())throw new AssertionError("explosion should excite room more strongly");if(projectile.sendGain()>=generic.sendGain())throw new AssertionError("projectile should use a shorter/drier room response");}
        finally{session.close();}System.out.println("[PASS] source category modifies legacy projection without changing scene physics");
    }

    private static void testExplicitNoShaderSelection() throws Exception {
        LegacyRuntimeConfig defaults=LegacyRuntimeConfig.defaults();if(defaults.packs().isEmpty()||!LegacyRuntimeConfig.DEFAULT_PACK.equals(defaults.pack()))throw new AssertionError("Reference shader must be selected on first launch");Path root=Files.createTempDirectory("acoustic-no-shader");Path cfg=root.resolve("runtime.properties");LegacyRuntimeConfig none=new LegacyRuntimeConfig(Collections.<String>emptyList(),"HIGH",18,10,10,24,true,false,Collections.<String,String>emptyMap());none.save(cfg);LegacyRuntimeConfig loaded=LegacyRuntimeConfig.loadOrCreate(cfg);if(!loaded.packs().isEmpty())throw new AssertionError("explicit empty shader stack was not preserved");LegacyShaderPackRuntime runtime=LegacyShaderPackRuntime.load(Paths.get("examples"),loaded);if(!runtime.disabled()||!runtime.livePipeline().passes().isEmpty())throw new AssertionError("empty stack did not become explicit vanilla-audio mode");Files.deleteIfExists(cfg);Files.deleteIfExists(root);System.out.println("[PASS] Reference shader defaults on, but user can explicitly select no acoustic shader");
    }

    private static void testHybridAutoCrossoverHonorsWaveTrust() throws Exception {
        MapPassContext c=new MapPassContext();float[] t=new float[dev.acoustic.api.material.FrequencyBands.COUNT];java.util.Arrays.fill(t,1f);c.put(StandardResources.DIRECT_PATH,new dev.acoustic.core.passes.DirectPathResult(3.0,3.0/343.0,t,0));
        c.put(StandardResources.WAVE_FIELD,new dev.acoustic.core.passes.WaveFieldResult(8,8,8,Collections.<Double>emptyList(),"test-fdtd",1.0/4000.0,new float[]{0,0,1,0},100.0));new dev.acoustic.core.passes.HybridResponsePass("AUTO",-1,1.0).execute(c);dev.acoustic.core.passes.HybridResponse h=c.require(StandardResources.HYBRID_RESPONSE);
        if(Math.abs(h.crossoverHz()-80.0)>1e-6||!h.waveEnabledForHybrid())throw new AssertionError("AUTO crossover did not respect conservative wave trust: "+h.crossoverHz());
        MapPassContext forced=new MapPassContext();forced.put(StandardResources.DIRECT_PATH,new dev.acoustic.core.passes.DirectPathResult(3.0,3.0/343.0,t,0));forced.put(StandardResources.WAVE_FIELD,c.require(StandardResources.WAVE_FIELD));new dev.acoustic.core.passes.HybridResponsePass("RAY_WAVE",250,1.0).execute(forced);if(forced.require(StandardResources.HYBRID_RESPONSE).crossoverHz()>95.0001)throw new AssertionError("manual crossover escaped wave solver trust limit");
        System.out.println("[PASS] hybrid AUTO crossover is bounded by wave-solver spatial trust");
    }

    private static void testHybridTimeDomainWaveEntersRir() throws Exception {
        float[] t=new float[dev.acoustic.api.material.FrequencyBands.COUNT];java.util.Arrays.fill(t,0.8f);dev.acoustic.core.passes.DirectPathResult direct=new dev.acoustic.core.passes.DirectPathResult(3.43,0.01,t,0);dev.acoustic.core.passes.DiffractionResult diff=new dev.acoustic.core.passes.DiffractionResult(false,3.43,new Vec3(0,0,0),new float[dev.acoustic.api.material.FrequencyBands.COUNT]);dev.acoustic.core.passes.EarlyReflectionField early=new dev.acoustic.core.passes.EarlyReflectionField(Collections.<dev.acoustic.core.passes.EarlyReflection>emptyList());dev.acoustic.core.passes.LateReverb late=new dev.acoustic.core.passes.LateReverb(new double[dev.acoustic.api.material.FrequencyBands.COUNT],new float[dev.acoustic.api.material.FrequencyBands.COUNT]);
        float[] waveImpulse=new float[200];waveImpulse[40]=1f;waveImpulse[55]=-0.6f;waveImpulse[70]=0.35f;dev.acoustic.core.passes.WaveFieldResult wave=new dev.acoustic.core.passes.WaveFieldResult(10,10,10,Collections.<Double>emptyList(),"fdtd/test",1.0/4000.0,waveImpulse,100.0);
        MapPassContext a=new MapPassContext();a.put(StandardResources.HYBRID_RESPONSE,new dev.acoustic.core.passes.HybridResponse(direct,diff,early,late,wave,"RAY_WAVE",80,1.0));new dev.acoustic.core.passes.ImpulseResponsePass(48000,0.2).execute(a);float[] hybrid=a.require(StandardResources.IMPULSE_RESPONSE).samples();
        MapPassContext b=new MapPassContext();b.put(StandardResources.HYBRID_RESPONSE,new dev.acoustic.core.passes.HybridResponse(direct,diff,early,late,wave,"RAY_ONLY",0,1.0));new dev.acoustic.core.passes.ImpulseResponsePass(48000,0.2).execute(b);float[] ray=b.require(StandardResources.IMPULSE_RESPONSE).samples();double delta=0;for(int i=0;i<hybrid.length;i++)delta+=Math.abs(hybrid[i]-ray[i]);if(delta<0.01)throw new AssertionError("time-domain wave response did not enter broadband RIR");
        System.out.println("[PASS] complementary time-domain wave/ray hybridization enters broadband RIR");
    }

}
