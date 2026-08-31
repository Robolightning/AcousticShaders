package dev.acoustic.tests;

import dev.acoustic.api.capability.Capabilities;
import dev.acoustic.api.capability.Capability;
import dev.acoustic.api.material.AcousticMaterial;
import dev.acoustic.api.environment.AcousticMedia;
import dev.acoustic.api.material.AcousticMaterials;
import dev.acoustic.api.material.resolve.AcousticTags;
import dev.acoustic.api.material.resolve.MaterialDescriptor;
import dev.acoustic.api.material.resolve.MaterialRule;
import dev.acoustic.api.environment.resolve.MediumRule;
import dev.acoustic.api.math.Vec3;
import dev.acoustic.api.pipeline.Pass;
import dev.acoustic.api.pipeline.PassContext;
import dev.acoustic.api.pipeline.ResourceKey;
import dev.acoustic.api.scene.AcousticVoxel;
import dev.acoustic.api.scene.AcousticBox;
import dev.acoustic.api.scene.AcousticShape;
import dev.acoustic.core.material.MaterialResolver;
import dev.acoustic.core.material.MaterialResolverCompiler;
import dev.acoustic.core.medium.MediumResolver;
import dev.acoustic.core.pack.PackOptions;
import dev.acoustic.core.pack.MaterialPack;
import dev.acoustic.core.pack.MediumPack;
import dev.acoustic.core.pack.PipelineDefinition;
import dev.acoustic.core.pack.ShaderPackManifest;
import dev.acoustic.core.pack.ShaderPackLoader;
import dev.acoustic.core.pack.LoadedShaderPack;
import dev.acoustic.core.pack.StrictJson;
import dev.acoustic.core.passes.DirectPathPass;
import dev.acoustic.core.passes.DirectPathResult;
import dev.acoustic.core.passes.EnvironmentRayPass;
import dev.acoustic.core.passes.EarlyReflectionPass;
import dev.acoustic.core.passes.EarlyReflectionField;
import dev.acoustic.core.passes.EarlyReflection;
import dev.acoustic.core.passes.DiffractionPass;
import dev.acoustic.core.passes.DiffractionResult;
import dev.acoustic.core.passes.LateReverbPass;
import dev.acoustic.core.passes.LateReverb;
import dev.acoustic.core.passes.WaveApproximationPass;
import dev.acoustic.core.passes.WaveFieldResult;
import dev.acoustic.core.passes.HybridResponse;
import dev.acoustic.core.dsp.FirConvolver;
import dev.acoustic.core.dsp.StereoSpatializer;
import dev.acoustic.core.dsp.PartitionedConvolver;
import dev.acoustic.core.dsp.PcmCodec;
import dev.acoustic.core.dsp.FoaRenderer;
import dev.acoustic.core.dsp.FoaImpulseResponse;
import dev.acoustic.core.dsp.SoftwareWetPcmRenderer;
import dev.acoustic.core.cache.TemporalResponseCache;
import dev.acoustic.core.source.SourceCandidate;
import dev.acoustic.core.source.SourceBudgetAllocator;
import dev.acoustic.core.runtime.AcousticRuntimeSession;
import dev.acoustic.core.runtime.ResolvedProfile;
import dev.acoustic.core.pack.ShaderPackValidator;
import dev.acoustic.core.pack.PackUiModel;
import dev.acoustic.core.runtime.AdaptiveAcousticRuntime;
import dev.acoustic.core.runtime.ShaderPackManager;
import dev.acoustic.core.runtime.WorkerTuner;
import dev.acoustic.core.diagnostics.DiagnosticReport;
import dev.acoustic.mc1122.LegacyBlockDescriptor;
import dev.acoustic.mc1122.LegacyCompatibilityProbe;
import dev.acoustic.mc1122.LegacySceneCapture;
import dev.acoustic.mc1122.LegacyWorldAccess;
import dev.acoustic.core.rir.ImpulseResponse;
import dev.acoustic.core.io.WavWriter;
import dev.acoustic.core.passes.ReflectionField;
import dev.acoustic.core.passes.StandardResources;
import dev.acoustic.core.pipeline.DefaultPipeline;
import dev.acoustic.core.pipeline.ExecutionReport;
import dev.acoustic.core.pipeline.MapPassContext;
import dev.acoustic.core.pipeline.ParallelPipelineExecutor;
import dev.acoustic.core.pipeline.PipelinePlan;
import dev.acoustic.core.quality.AdaptiveBudgetController;
import dev.acoustic.core.scene.ImmutableVoxelSnapshot;
import dev.acoustic.core.scene.SnapshotExchange;
import dev.acoustic.core.scene.VoxelSnapshotBuilder;
import dev.acoustic.core.trace.RayHit;
import dev.acoustic.core.trace.MediumBoundaryRaycast;
import dev.acoustic.core.trace.MediumPathIntegrator;
import dev.acoustic.core.trace.VoxelRaycast;
import dev.acoustic.testkit.VoxelTestScene;
import dev.acoustic.api.source.AcousticSourceProfile;
import dev.acoustic.platform.AcousticPlatformAdapter;
import dev.acoustic.platform.ListenerSnapshot;
import dev.acoustic.platform.PlatformFrameAdapter;
import dev.acoustic.platform.PlatformFrameSnapshot;
import dev.acoustic.platform.PlatformFrameValidator;
import dev.acoustic.platform.SceneCaptureRequest;
import dev.acoustic.platform.SoundSourceSnapshot;
import dev.acoustic.core.runtime.PlatformAdapterRuntime;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class HeadlessTestSuite {
    private static int passed;

    public static void main(String[] args) throws Exception {
        testEmptyDirectPath();
        testLiquidPropagation();
        testStoneOcclusion();
        testVoxelRaycastNormal();
        testPartialBlockGeometry();
        testPartialThicknessTransmission();
        testEnvironmentRayField();
        testPartitionedRayScheduling();
        testPipelineParallelismAndPlan();
        testProfiling();
        testMaterialValidation();
        testMaterialResolutionPriority();
        testImmutableSnapshot();
        testSnapshotExchange();
        testCapabilities();
        testPlatformFrameBoundary();
        testPlatformDefaultFrameCapture();
        testPlatformAdapterRuntime();
        testAdaptiveBudget();
        testStrictJsonAndManifest();
        testPipelineDefinition();
        testPackOptions();
        testMaterialPack();
        testMediumPack();
        testReferencePackLoader();
        testEarlyReflections();
        testDiffraction();
        testWaveApproximation();
        testLateReverb();
        testDspPrimitives();
        testTemporalCache();
        testProfileResolution();
        testEndToEndRuntime();
        testZipPackLoading();
        testPackValidation();
        testPackManager();
        testLegacyMaterialNormalization();
        testLegacyCompatibilityProbe();
        testWorkerTuner();
        testDiagnostics();
        testImpulseResponse();
        testLegacySceneCapture();
        testPackMaterialResolverCompiler();
        testPassFactoryExtension();
        testPackUiModel();
        testAdaptiveRuntimeProfileSwitch();
        testOptionalWaveFallback();
        testWavExport();
        testWorkerDeterminism();
        testSchedulerFailureCancellation();
        testPartitionedConvolution();
        testPcmCodec();
        testFoaWetRendering();
        testSourceBudgeting();
        System.out.println("PASS: " + passed + " headless tests");
    }

    private static void testEmptyDirectPath() throws Exception {
        MapPassContext ctx = baseContext(VoxelTestScene.builder().build(), new Vec3(0.5,0.5,0.5), new Vec3(10.5,0.5,0.5));
        run(new DirectPathPass(), ctx);
        DirectPathResult result = ctx.require(StandardResources.DIRECT_PATH);
        assertNear(10.0, result.distanceMeters, 1e-9, "distance");
        assertNear(10.0 / 343.0, result.delaySeconds, 1e-12, "delay");
        assertTrue(result.solidCells == 0, "empty path must not hit solids");
        for (float v : result.transmission) assertNear(1.0, v, 1e-6, "empty transmission");
        pass("empty direct path");
    }


    private static void testLiquidPropagation() throws Exception {
        VoxelTestScene.Builder waterBuilder=VoxelTestScene.builder();
        for(int x=0;x<=9;x++) waterBuilder.water(x,0,0);
        MapPassContext water=baseContext(waterBuilder.build(),new Vec3(.5,.5,.5),new Vec3(9.5,.5,.5));
        run(new DirectPathPass(),water);
        DirectPathResult submerged=water.require(StandardResources.DIRECT_PATH);
        assertNear(9.0/AcousticMedia.WATER.speedOfSoundMetersPerSecond(),submerged.delaySeconds,1e-7,"underwater travel time");
        assertNear(9.0,submerged.liquidMeters,1e-7,"underwater path length");
        assertNear(0.0,submerged.airMeters,1e-7,"underwater air path");
        assertTrue(submerged.mediumBoundaryCount==0&&submerged.sourceSubmerged()&&submerged.listenerSubmerged(),"same-water path medium metadata");
        assertTrue(submerged.transmission[7]>.99f,"short underwater path should not fake strong high-frequency bulk absorption");

        VoxelTestScene.Builder lavaBuilder=VoxelTestScene.builder();
        for(int x=0;x<=9;x++) lavaBuilder.lava(x,0,0);
        MapPassContext lava=baseContext(lavaBuilder.build(),new Vec3(.5,.5,.5),new Vec3(9.5,.5,.5));
        run(new DirectPathPass(),lava);
        DirectPathResult molten=lava.require(StandardResources.DIRECT_PATH);
        assertNear(9.0/AcousticMedia.LAVA.speedOfSoundMetersPerSecond(),molten.delaySeconds,1e-7,"lava travel time");
        assertTrue(molten.sourceMediumId.equals(AcousticMedia.LAVA.id())&&molten.listenerMediumId.equals(AcousticMedia.LAVA.id()),"lava medium identity must survive the direct path");
        assertTrue(AcousticMedia.LAVA.characteristicImpedanceRayl()>AcousticMedia.WATER.characteristicImpedanceRayl(),"molten basalt fallback should have higher impedance than water");

        VoxelTestScene.Builder mixedBuilder=VoxelTestScene.builder();
        for(int x=5;x<=9;x++) mixedBuilder.water(x,0,0);
        MapPassContext mixed=baseContext(mixedBuilder.build(),new Vec3(.5,.5,.5),new Vec3(9.5,.5,.5));
        run(new DirectPathPass(),mixed);
        DirectPathResult crossing=mixed.require(StandardResources.DIRECT_PATH);
        double expectedDelay=4.5/343.0+4.5/AcousticMedia.WATER.speedOfSoundMetersPerSecond();
        assertNear(expectedDelay,crossing.delaySeconds,1e-7,"air-water mixed travel time");
        assertTrue(crossing.mediumBoundaryCount==1&&!crossing.sourceSubmerged()&&crossing.listenerSubmerged(),"air-water boundary metadata");
        assertTrue(crossing.transmission[0]>.02f&&crossing.transmission[0]<.05f,"normal-incidence air-water impedance mismatch");

        VoxelTestScene.Builder obliqueBuilder=VoxelTestScene.builder();
        for(int y=1;y<=3;y++) for(int x=0;x<=9;x++) obliqueBuilder.water(x,y,0);
        MapPassContext oblique=baseContext(obliqueBuilder.build(),new Vec3(.5,.5,.5),new Vec3(8.5,2.5,.5));
        run(new DirectPathPass(),oblique);
        DirectPathResult critical=oblique.require(StandardResources.DIRECT_PATH);
        assertTrue(critical.mediumBoundaryCount==1,"oblique path should cross one water surface");
        assertTrue(critical.transmission[0]>.005f&&critical.transmission[0]<.03f,"point-source air-water path should refract to a transmissive incidence");
        assertTrue(critical.distanceMeters>new Vec3(.5,.5,.5).distance(new Vec3(8.5,2.5,.5)),"Snell path should bend and exceed straight geometric distance");

        VoxelTestScene.Builder slabBuilder=VoxelTestScene.builder();
        for(int x=4;x<=5;x++) for(int y=-4;y<=7;y++) slabBuilder.water(x,y,0);
        VoxelTestScene slabScene=slabBuilder.build();
        Vec3 slabSource=new Vec3(.5,.5,.5),slabListener=new Vec3(9.5,3.5,.5);
        MediumPathIntegrator.Result unbent=MediumPathIntegrator.integrate(slabScene,slabSource,slabListener,dev.acoustic.api.environment.AcousticEnvironment.STANDARD);
        MapPassContext slab=baseContext(slabScene,slabSource,slabListener);
        run(new DirectPathPass(),slab);
        DirectPathResult through=slab.require(StandardResources.DIRECT_PATH);
        assertTrue(through.mediumBoundaryCount==2,"air-water-air slab must preserve both interfaces");
        assertTrue(through.delaySeconds<unbent.getDelaySeconds(),"parallel multi-interface Snell path must minimize travel time");
        assertTrue(through.distanceMeters>slabSource.distance(slabListener),"fast-water slab path should bend and exceed straight geometric distance");
        assertTrue(through.transmission[0]>.0002f&&through.transmission[0]<.005f,"two impedance-mismatched interfaces must strongly attenuate through-water aerial path");

        VoxelTestScene.Builder surfaceBuilder=VoxelTestScene.builder();
        for(int x=-5;x<=5;x++) for(int y=-3;y<=-1;y++) for(int z=-5;z<=5;z++) surfaceBuilder.water(x,y,z);
        MapPassContext surface=baseContext(surfaceBuilder.build(),new Vec3(.5,-.5,.5),new Vec3(.5,-.5,.5));
        run(new EnvironmentRayPass(16,1,8.0,1.0e-6,"CPU"),surface);
        ReflectionField reflected=surface.require(StandardResources.REFLECTION_FIELD);
        assertTrue(!reflected.samples().isEmpty(),"underwater environment rays must reflect from the water surface even without solid blocks");
        assertTrue(reflected.samples().get(0).energy(0)>.99f,"water-air surface should be an almost perfect acoustic reflector");
        assertTrue(reflected.samples().get(0).delaySeconds()<reflected.samples().get(0).pathDistance()/343.0,"underwater reflection delay must use water sound speed");

        VoxelTestScene partial=VoxelTestScene.builder().partialMedium(0,0,0,AcousticMedia.WATER,0.5).build();
        Vec3 below=new Vec3(.5,.25,.5),above=new Vec3(.5,.75,.5);
        assertTrue(MediumPathIntegrator.mediumAt(partial,below).id().equals(AcousticMedia.WATER.id()),"point below partial fluid surface must be submerged");
        assertTrue(MediumPathIntegrator.mediumAt(partial,above).id().equals(AcousticMedia.AIR.id()),"point above partial fluid surface must remain air in same voxel");
        MediumPathIntegrator.Result partialPath=MediumPathIntegrator.integrate(partial,below,above,dev.acoustic.api.environment.AcousticEnvironment.STANDARD);
        assertNear(.25,partialPath.getLiquidMeters(),1e-8,"partial voxel liquid path length");
        assertNear(.25,partialPath.getAirMeters(),1e-8,"partial voxel air path length");
        assertTrue(partialPath.getInterfaceCount()==1,"partial fluid free surface must create one acoustic interface");
        assertNear(.5,partialPath.getBoundaries().get(0).getCoordinate(),1e-8,"partial fluid surface coordinate");
        MediumBoundaryRaycast.Hit partialHit=MediumBoundaryRaycast.first(partial,below,new Vec3(0,1,0),1.0);
        assertTrue(partialHit!=null,"submerged ray must hit partial fluid free surface");
        assertNear(.25,partialHit.getDistance(),1e-8,"partial fluid ray-surface distance");
        assertTrue(partialHit.getNormal().equals(new Vec3(0,-1,0)),"fluid-exit normal must point back into incident liquid");

        VoxelTestScene connected=VoxelTestScene.builder()
            .partialMedium(0,0,0,AcousticMedia.WATER,0.30)
            .partialMedium(0,1,0,AcousticMedia.WATER,0.30)
            .build();
        Vec3 connectedBottom=new Vec3(.5,.10,.5),connectedTop=new Vec3(.5,1.20,.5);
        assertTrue(MediumPathIntegrator.mediumAt(connected,new Vec3(.5,.90,.5)).id().equals(AcousticMedia.WATER.id()),"same-medium voxel above must remove fake internal air sheet");
        MediumPathIntegrator.Result connectedPath=MediumPathIntegrator.integrate(connected,connectedBottom,connectedTop,dev.acoustic.api.environment.AcousticEnvironment.STANDARD);
        assertNear(1.10,connectedPath.getLiquidMeters(),1e-8,"connected partial liquid column length");
        assertNear(0.0,connectedPath.getAirMeters(),1e-8,"connected partial liquid column must not contain internal air");
        assertTrue(connectedPath.getInterfaceCount()==0,"connected same-medium voxels must not create an internal acoustic interface");
        assertTrue(MediumBoundaryRaycast.first(connected,connectedBottom,new Vec3(0,1,0),1.10)==null,"connected same-medium column must not expose an internal liquid surface");

        VoxelTestScene differentAbove=VoxelTestScene.builder()
            .partialMedium(0,0,0,AcousticMedia.WATER,0.30)
            .partialMedium(0,1,0,AcousticMedia.LAVA,0.30)
            .build();
        assertTrue(MediumPathIntegrator.mediumAt(differentAbove,new Vec3(.5,.90,.5)).id().equals(AcousticMedia.AIR.id()),"different medium above must not fill lower liquid voxel");

        VoxelTestScene sloped=VoxelTestScene.builder()
            .partialMedium(0,0,0,AcousticMedia.WATER,0.75)
            .partialMedium(1,0,0,AcousticMedia.WATER,0.25)
            .build();
        Vec3 slopeStart=new Vec3(.10,.60,.50);
        MediumBoundaryRaycast.Hit slopeHit=MediumBoundaryRaycast.first(sloped,slopeStart,new Vec3(1,0,0),1.20);
        assertTrue(slopeHit!=null,"neighbor-aware liquid heightfield must expose a sloped free surface");
        assertNear(.50,slopeHit.getDistance(),1e-7,"bilinear liquid surface crossing distance");
        assertTrue(slopeHit.getNormal().x<-.20&&slopeHit.getNormal().y<-.90,"sloped fluid-exit normal must point back into incident liquid");
        MediumPathIntegrator.Result slopePath=MediumPathIntegrator.integrate(sloped,slopeStart,new Vec3(1.10,.60,.50),dev.acoustic.api.environment.AcousticEnvironment.STANDARD);
        assertNear(.50,slopePath.getLiquidMeters(),1e-7,"neighbor-smoothed liquid path length");
        assertNear(.50,slopePath.getAirMeters(),1e-7,"neighbor-smoothed air path length");
        assertTrue(slopePath.getInterfaceCount()==1,"smoothed free surface should create exactly one interface");
        pass("air/water volume propagation, layered Snell refraction and liquid-surface reflection");
    }

    private static void testStoneOcclusion() throws Exception {
        VoxelTestScene scene = VoxelTestScene.builder().solid(5,0,0, AcousticMaterials.STONE).build();
        MapPassContext ctx = baseContext(scene, new Vec3(0.5,0.5,0.5), new Vec3(10.5,0.5,0.5));
        run(new DirectPathPass(), ctx);
        DirectPathResult result = ctx.require(StandardResources.DIRECT_PATH);
        assertTrue(result.solidCells == 1, "one stone voxel should be crossed");
        assertTrue(result.transmission[0] < 0.02f, "stone should strongly occlude direct sound in M0 model");
        pass("stone occlusion");
    }


    private static void testVoxelRaycastNormal() {
        VoxelTestScene scene=VoxelTestScene.builder().solid(3,0,0,AcousticMaterials.STONE).build();
        RayHit hit=VoxelRaycast.firstSolid(scene,new Vec3(0.5,0.5,0.5),new Vec3(1,0,0),10.0);
        assertTrue(hit!=null,"ray must hit stone");
        assertNear(2.5,hit.distance(),1e-12,"ray hit distance");
        assertTrue(new Vec3(-1,0,0).equals(hit.normal()),"entry face normal");
        pass("voxel first-hit ray query");
    }

    private static void testPartialBlockGeometry() {
        AcousticShape slab=AcousticShape.of(new AcousticBox(0,0,0,1,0.5,1));
        VoxelTestScene scene=VoxelTestScene.builder().shaped(3,0,0,AcousticMaterials.STONE,slab).build();
        RayHit low=VoxelRaycast.firstSolid(scene,new Vec3(0.5,0.25,0.5),new Vec3(1,0,0),10.0);
        RayHit high=VoxelRaycast.firstSolid(scene,new Vec3(0.5,0.75,0.5),new Vec3(1,0,0),10.0);
        assertTrue(low!=null,"ray through occupied slab half must hit");assertNear(2.5,low.distance(),1e-9,"slab hit distance");assertTrue(high==null,"ray through empty slab half must pass");
        pass("exact partial-block ray geometry preserves slab gaps");
    }

    private static void testPartialThicknessTransmission() throws Exception {
        AcousticShape pane=AcousticShape.of(new AcousticBox(0.4375,0,0,0.5625,1,1));
        VoxelTestScene thin=VoxelTestScene.builder().shaped(3,0,0,AcousticMaterials.GLASS,pane).build();
        VoxelTestScene full=VoxelTestScene.builder().solid(3,0,0,AcousticMaterials.GLASS).build();
        MapPassContext a=baseContext(thin,new Vec3(0.5,0.5,0.5),new Vec3(6.5,0.5,0.5));run(new DirectPathPass(),a);
        MapPassContext b=baseContext(full,new Vec3(0.5,0.5,0.5),new Vec3(6.5,0.5,0.5));run(new DirectPathPass(),b);
        DirectPathResult thinResult=a.require(StandardResources.DIRECT_PATH),fullResult=b.require(StandardResources.DIRECT_PATH);
        assertTrue(thinResult.solidCells==1,"thin pane intersection should count one blocker");assertNear(0.125,thinResult.occupiedMeters,1e-9,"thin pane exact occupied length");assertNear(1.0,fullResult.occupiedMeters,1e-9,"full block exact occupied length");assertTrue(thinResult.transmission[4]>fullResult.transmission[4],"thin pane must attenuate less than a full meter of same material");assertTrue(thinResult.transmission[4]<1f,"thin pane must still attenuate direct sound");
        pass("direct transmission scales with exact occupied material thickness");
    }

    private static void testEnvironmentRayField() throws Exception {
        VoxelTestScene.Builder b=VoxelTestScene.builder();
        for(int a=-2;a<=2;a++) for(int c=-2;c<=2;c++) {
            b.solid(-2,a,c,AcousticMaterials.STONE).solid(2,a,c,AcousticMaterials.STONE);
            b.solid(a,-2,c,AcousticMaterials.STONE).solid(a,2,c,AcousticMaterials.STONE);
            b.solid(a,c,-2,AcousticMaterials.STONE).solid(a,c,2,AcousticMaterials.STONE);
        }
        MapPassContext ctx=baseContext(b.build(),new Vec3(0.5,0.5,0.5),new Vec3(0.5,0.5,0.5));
        run(new EnvironmentRayPass(64,2,10.0,0.0001),ctx);
        ReflectionField field=ctx.require(StandardResources.REFLECTION_FIELD);
        assertTrue(field.raysTraced()==64,"configured ray count");
        assertTrue(field.samples().size()>=64,"closed box should produce at least one hit per ray");
        assertTrue(field.samples().get(0).energy(0)<1f,"reflection must lose energy");
        assertTrue(field.samples().get(0).delaySeconds()>0,"reflection delay must be positive");
        pass("deterministic multi-bounce environment ray field");
    }


    private static void testPartitionedRayScheduling() throws Exception {
        VoxelTestScene.Builder b=VoxelTestScene.builder();
        for(int a=-2;a<=2;a++) for(int c=-2;c<=2;c++){b.solid(-2,a,c,AcousticMaterials.STONE).solid(2,a,c,AcousticMaterials.STONE);b.solid(a,-2,c,AcousticMaterials.STONE).solid(a,2,c,AcousticMaterials.STONE);b.solid(a,c,-2,AcousticMaterials.STONE).solid(a,c,2,AcousticMaterials.STONE);}
        MapPassContext ctx=baseContext(b.build(),new Vec3(0.5,0.5,0.5),new Vec3(0.5,0.5,0.5));
        ParallelPipelineExecutor executor=new ParallelPipelineExecutor(4);
        try {
            ExecutionReport report=executor.executeProfiled(new DefaultPipeline(Collections.<Pass>singletonList(new EnvironmentRayPass(256,2,10.0,0.0001))),ctx);
            assertTrue("parallel[4]".equals(report.timing("standard.environment_rays").threadName()),"heavy pass should be partitioned across runtime workers");
            assertTrue(ctx.require(StandardResources.REFLECTION_FIELD).raysTraced()==256,"partition combine must preserve ray count");
        } finally { executor.close(); }
        pass("runtime-owned intra-pass parallelism");
    }

    private static void testPipelineParallelismAndPlan() throws Exception {
        final ResourceKey<String> A = new ResourceKey<String>("test.a", String.class);
        final ResourceKey<String> B = new ResourceKey<String>("test.b", String.class);
        final ResourceKey<String> C = new ResourceKey<String>("test.c", String.class);
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        Pass pa = waitingPass("a", A, started, release);
        Pass pb = waitingPass("b", B, started, release);
        Pass join = new Pass() {
            public String id(){return "join";}
            public Set<ResourceKey<?>> reads(){return new LinkedHashSet<ResourceKey<?>>(Arrays.<ResourceKey<?>>asList(A,B));}
            public Set<ResourceKey<?>> writes(){return Collections.<ResourceKey<?>>singleton(C);}
            public void execute(PassContext c){c.put(C,c.require(A)+c.require(B));}
        };
        DefaultPipeline pipeline=new DefaultPipeline(Arrays.asList(pa,pb,join));
        PipelinePlan plan=PipelinePlan.compile(pipeline);
        assertTrue(plan.levels().size()==2,"parallel writers and join should form two levels");
        assertTrue(plan.levels().get(0).size()==2,"first level must contain independent passes");
        final MapPassContext context = new MapPassContext();
        final ParallelPipelineExecutor executor = new ParallelPipelineExecutor(2);
        Thread runner = new Thread(new Runnable(){public void run(){try{executor.execute(pipeline,context);}catch(Exception e){throw new RuntimeException(e);}}});
        runner.start();
        assertTrue(started.await(2, TimeUnit.SECONDS), "independent passes did not start concurrently");
        release.countDown();
        runner.join(2000);
        executor.close();
        assertTrue(!runner.isAlive(), "pipeline did not finish");
        assertTrue("ab".equals(context.require(C)), "join pass result");
        pass("compiled parallel DAG scheduling");
    }

    private static void testProfiling() throws Exception {
        MapPassContext ctx=baseContext(VoxelTestScene.builder().build(),new Vec3(0.5,0.5,0.5),new Vec3(2.5,0.5,0.5));
        ParallelPipelineExecutor executor=new ParallelPipelineExecutor(2);
        try {
            ExecutionReport report=executor.executeProfiled(new DefaultPipeline(Collections.<Pass>singletonList(new DirectPathPass())),ctx);
            assertTrue(report.elapsedNanos()>0,"profile total must be positive");
            assertTrue(report.timing("standard.direct_path")!=null,"pass timing must be present");
            assertTrue(report.timing("standard.direct_path").threadName().startsWith("acoustic-worker"),"pass must run on runtime worker");
        } finally { executor.close(); }
        pass("per-pass execution profiling");
    }

    private static Pass waitingPass(final String value, final ResourceKey<String> out, final CountDownLatch started, final CountDownLatch release) {
        return new Pass(){
            public String id(){return "write-"+value;}
            public Set<ResourceKey<?>> reads(){return Collections.emptySet();}
            public Set<ResourceKey<?>> writes(){return Collections.<ResourceKey<?>>singleton(out);}
            public void execute(PassContext c) throws Exception {started.countDown(); release.await(); c.put(out,value);}
        };
    }

    private static void testMaterialValidation() {
        boolean failed=false;
        try { new AcousticMaterial("bad", new float[]{1}, 0, 0); }
        catch (IllegalArgumentException expected) { failed=true; }
        assertTrue(failed, "invalid spectrum must be rejected");
        pass("material validation");
    }

    private static void testMaterialResolutionPriority() {
        Set<String> tags=new HashSet<String>(Collections.singletonList(AcousticTags.METAL));
        Set<String> ore=new HashSet<String>(Collections.singletonList("blockCopper"));
        MaterialDescriptor d=new MaterialDescriptor("example:copper_grate",tags,ore,true);
        MaterialRule broad=new MaterialRule(10,MaterialRule.MatchKind.TAG,AcousticTags.METAL,AcousticMaterials.METAL);
        MaterialRule exact=new MaterialRule(100,MaterialRule.MatchKind.REGISTRY_ID,"example:copper_grate",AcousticMaterials.GLASS);
        MaterialResolver resolver=new MaterialResolver(Arrays.asList(broad,exact),AcousticMaterials.STONE,AcousticMaterials.AIR);
        assertTrue(resolver.resolve(d)==AcousticMaterials.GLASS,"explicit registry rule must override semantic fallback");
        MaterialDescriptor unknown=new MaterialDescriptor("mod:unknown",Collections.<String>emptySet(),Collections.<String>emptySet(),true);
        assertTrue(resolver.resolve(unknown)==AcousticMaterials.STONE,"unknown solid must use solid fallback");
        pass("cross-mod material rule priority");
    }

    private static void testImmutableSnapshot() {
        VoxelTestScene.Builder builder=VoxelTestScene.builder().solid(1,1,1,AcousticMaterials.WOOD);
        VoxelTestScene source=builder.build();
        AcousticVoxel air=new AcousticVoxel(false,AcousticMaterials.AIR);
        ImmutableVoxelSnapshot snapshot=VoxelSnapshotBuilder.copyRegion(source,0,0,0,4,4,4,air,42L);
        assertTrue(snapshot.generation()==42L,"snapshot generation");
        assertTrue(snapshot.voxelAt(1,1,1).solid(),"copied solid voxel");
        assertTrue(snapshot.voxelAt(100,100,100)==air,"outside policy must be deterministic");
        VoxelTestScene changed=VoxelTestScene.builder().solid(2,2,2,AcousticMaterials.STONE).build();
        assertTrue(changed.voxelAt(2,2,2).solid() && !snapshot.voxelAt(2,2,2).solid(),"snapshot must not observe later scene changes");
        pass("immutable worker-safe scene snapshot");
    }

    private static void testSnapshotExchange() {
        SnapshotExchange<String> exchange=new SnapshotExchange<String>("old");
        assertTrue("old".equals(exchange.current()),"initial exchange value");
        assertTrue("old".equals(exchange.publish("new")),"publish returns previous value");
        assertTrue("new".equals(exchange.current()),"new value atomically visible");
        pass("lock-free snapshot publication");
    }

    private static void testCapabilities() {
        Capabilities capabilities = new Capabilities(EnumSet.of(Capability.PARALLEL_CPU, Capability.RAY_QUERY));
        capabilities.require(EnumSet.of(Capability.RAY_QUERY));
        boolean failed = false;
        try { capabilities.require(EnumSet.of(Capability.GPU_COMPUTE)); }
        catch (UnsupportedOperationException expected) { failed = true; }
        assertTrue(failed, "missing required capability must fail");
        pass("capability negotiation");
    }

    private static void testPlatformFrameBoundary() {
        boolean badListener=false;
        try { new ListenerSnapshot(new Vec3(Double.NaN,0,0),new Vec3(0,0,1),new Vec3(0,1,0)); }
        catch (IllegalArgumentException expected) { badListener=true; }
        assertTrue(badListener,"listener snapshot must reject non-finite coordinates");

        boolean badListenerVelocity=false;
        try { new ListenerSnapshot(new Vec3(0,0,0),new Vec3(0,0,1),new Vec3(0,1,0),new Vec3(0,Double.NaN,0)); }
        catch (IllegalArgumentException expected) { badListenerVelocity=true; }
        assertTrue(badListenerVelocity,"listener snapshot must reject non-finite velocity");

        boolean badSource=false;
        try { new SoundSourceSnapshot(1L,"test:tone",new Vec3(0,0,0),Float.POSITIVE_INFINITY); }
        catch (IllegalArgumentException expected) { badSource=true; }
        assertTrue(badSource,"source snapshot must reject non-finite gain");

        boolean badSourceVelocity=false;
        try { new SoundSourceSnapshot(2L,"test:tone",new Vec3(0,0,0),1f,AcousticSourceProfile.GENERIC,new Vec3(Double.POSITIVE_INFINITY,0,0)); }
        catch (IllegalArgumentException expected) { badSourceVelocity=true; }
        assertTrue(badSourceVelocity,"source snapshot must reject non-finite velocity");

        ListenerSnapshot listener=new ListenerSnapshot(new Vec3(0.5,0.5,0.5),new Vec3(0,0,1),new Vec3(0,1,0));
        SoundSourceSnapshot a=new SoundSourceSnapshot(7L,"test:a",new Vec3(1.5,0.5,0.5),1f);
        SoundSourceSnapshot b=new SoundSourceSnapshot(7L,"test:b",new Vec3(2.5,0.5,0.5),.5f);
        PlatformFrameSnapshot duplicate=new PlatformFrameSnapshot(VoxelTestScene.builder().build(),listener,Arrays.asList(a,b),3L,9L);
        boolean duplicateFailed=false;
        try { PlatformFrameValidator.requireValid(duplicate); }
        catch (IllegalArgumentException expected) { duplicateFailed=true; }
        assertTrue(duplicateFailed,"platform frame must reject duplicate stable source ids");
        pass("cross-version platform frame boundary validation");
    }

    private static void testPlatformDefaultFrameCapture() {
        final VoxelTestScene scene=VoxelTestScene.builder().solid(1,0,0,AcousticMaterials.WOOD).build();
        final java.util.List<SoundSourceSnapshot> liveSources=new java.util.ArrayList<SoundSourceSnapshot>();
        liveSources.add(new SoundSourceSnapshot(11L,"test:source",new Vec3(1.5,.5,.5),.8f));
        AcousticPlatformAdapter adapter=new AcousticPlatformAdapter(){
            public String platformId(){return "test:legacy-default";}
            public Capabilities capabilities(){return new Capabilities(EnumSet.of(Capability.RAY_QUERY));}
            public dev.acoustic.api.scene.AcousticScene captureScene(SceneCaptureRequest request){return scene;}
            public ListenerSnapshot captureListener(){return new ListenerSnapshot(new Vec3(.5,.5,.5),new Vec3(0,0,1),new Vec3(0,1,0));}
            public java.util.List<SoundSourceSnapshot> captureActiveSources(){return liveSources;}
        };
        PlatformFrameSnapshot frame=adapter.captureFrame(new SceneCaptureRequest(0,0,0,4,4,4));
        liveSources.clear();
        assertTrue(frame.scene()==scene,"default frame capture must preserve the captured scene instance");
        assertTrue(frame.sources().size()==1&&frame.sources().get(0).id()==11L,"frame must defensively snapshot active-source list");
        assertNear(0,frame.sources().get(0).velocity().length(),1e-12,"legacy source constructor must default velocity to zero");
        assertNear(0,frame.listener().velocity().length(),1e-12,"legacy listener constructor must default velocity to zero");
        assertTrue(frame.worldEpoch()==0L&&frame.frameSequence()==scene.revision(),"legacy default frame must derive ordering from scene revision");
        PlatformFrameValidator.requireValid(frame);
        pass("backward-compatible atomic platform frame capture");
    }

    private static void testPlatformAdapterRuntime() throws Exception {
        final VoxelTestScene scene=closedRoom(3);
        final long[] sequence=new long[]{4L};
        final long[] epoch=new long[]{12L};
        AcousticPlatformAdapter adapter=new AcousticPlatformAdapter(){
            public String platformId(){return "test:modern-adapter";}
            public Capabilities capabilities(){return new Capabilities(EnumSet.of(Capability.RAY_QUERY,Capability.PARALLEL_CPU));}
            public dev.acoustic.api.scene.AcousticScene captureScene(SceneCaptureRequest request){return scene;}
            public ListenerSnapshot captureListener(){return new ListenerSnapshot(new Vec3(.5,.5,.5),new Vec3(0,0,1),new Vec3(0,1,0));}
            public java.util.List<SoundSourceSnapshot> captureActiveSources(){return Collections.singletonList(new SoundSourceSnapshot(19L,"test:spatial",new Vec3(1.5,.5,.5),1f));}
            public PlatformFrameSnapshot captureFrame(SceneCaptureRequest request){
                return new PlatformFrameSnapshot(scene,captureListener(),captureActiveSources(),epoch[0],sequence[0]);
            }
        };
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));
        PlatformAdapterRuntime runtime=new PlatformAdapterRuntime(adapter,pack,"LOW",2,new SceneCaptureRequest(-3,-3,-3,7,7,7));
        try {
            PlatformAdapterRuntime.PlatformFrameResult first=runtime.captureAndProcess();
            assertTrue(first.frame().worldEpoch()==12L&&first.frame().frameSequence()==4L,"runtime must preserve adapter frame identity");
            assertTrue(first.sourceResults().size()==1,"runtime must process every source from the captured frame");
            assertTrue(first.sourceResults().get(0).source().id()==19L,"source/result association must remain stable");
            assertTrue(first.sourceResults().get(0).result().response().direct()!=null,"portable shader DAG must execute through adapter bridge");
            sequence[0]=3L;
            boolean staleFailed=false;
            try { runtime.captureAndProcess(); }
            catch (IllegalArgumentException expected) { staleFailed=true; }
            assertTrue(staleFailed,"runtime must reject sequence regression inside one world epoch");
            epoch[0]=13L;
            sequence[0]=0L;
            PlatformAdapterRuntime.PlatformFrameResult newWorld=runtime.captureAndProcess();
            assertTrue(newWorld.frame().worldEpoch()==13L&&newWorld.frame().frameSequence()==0L,"new world epoch must permit sequence restart");
            AcousticPlatformAdapter newWorldAdapter=new AcousticPlatformAdapter(){
                public String platformId(){return "test:modern-adapter";}
                public Capabilities capabilities(){return new Capabilities(EnumSet.of(Capability.RAY_QUERY));}
                public dev.acoustic.api.scene.AcousticScene captureScene(SceneCaptureRequest request){return scene;}
                public ListenerSnapshot captureListener(){return new ListenerSnapshot(new Vec3(.5,.5,.5),new Vec3(0,0,1),new Vec3(0,1,0));}
                public java.util.List<SoundSourceSnapshot> captureActiveSources(){return Collections.<SoundSourceSnapshot>emptyList();}
            };
            assertTrue(newWorldAdapter.captureFrame(new SceneCaptureRequest(0,0,0,1,1,1)).frameSequence()==scene.revision(),"default adapter bridge remains available to simple ports");
        } finally { runtime.close(); }

        PlatformFrameAdapter atomicOnly=new PlatformFrameAdapter(){
            public String platformId(){return "test:atomic-only";}
            public Capabilities capabilities(){return new Capabilities(EnumSet.of(Capability.RAY_QUERY,Capability.PARALLEL_CPU));}
            public PlatformFrameSnapshot captureFrame(SceneCaptureRequest request){
                AcousticSourceProfile profile=new AcousticSourceProfile(
                    "test:explosion","explosion",new float[]{1f,1f,1f,1f,1f,1f,1f,1f},
                    1.5f,1f,1f,1f,1f,2f,1f,1f,2f,false
                );
                return new PlatformFrameSnapshot(
                    scene,
                    new ListenerSnapshot(new Vec3(.5,.5,.5),new Vec3(0,0,4),new Vec3(0,3,0),new Vec3(.125,-.25,.5)),
                    Collections.singletonList(new SoundSourceSnapshot(23L,"test:atomic",new Vec3(1.5,.5,.5),.375f,profile,new Vec3(2,-1,.25))),
                    20L,
                    1L
                );
            }
        };
        PlatformAdapterRuntime atomicRuntime=new PlatformAdapterRuntime(atomicOnly,pack,"LOW",1,new SceneCaptureRequest(-3,-3,-3,7,7,7));
        try {
            PlatformAdapterRuntime.PlatformFrameResult atomic=atomicRuntime.captureAndProcess();
            assertTrue(atomic.frame().worldEpoch()==20L&&atomic.sourceResults().size()==1&&atomic.sourceResults().get(0).source().id()==23L,"modern frame-only adapter must not require split capture methods");
            assertTrue("test:explosion".equals(atomic.sourceResults().get(0).source().profile().id()),"atomic source snapshot must preserve frontend source profile");
            assertTrue("test:explosion".equals(atomic.sourceResults().get(0).result().sourceBehavior().id()),"portable shader DAG must receive the adapter source profile");
            assertNear(.375,atomic.sourceResults().get(0).result().sourceGain(),1e-6,"portable shader DAG must receive exact adapter source gain");
            Vec3 runtimeForward=atomic.sourceResults().get(0).result().listenerForward();
            Vec3 runtimeUp=atomic.sourceResults().get(0).result().listenerUp();
            assertNear(0,runtimeForward.x,1e-9,"listener forward x");
            assertNear(0,runtimeForward.y,1e-9,"listener forward y");
            assertNear(1,runtimeForward.z,1e-9,"listener forward must be normalized through adapter runtime");
            assertNear(0,runtimeUp.x,1e-9,"listener up x");
            assertNear(1,runtimeUp.y,1e-9,"listener up must be normalized through adapter runtime");
            assertNear(0,runtimeUp.z,1e-9,"listener up z");
            Vec3 runtimeSourceVelocity=atomic.sourceResults().get(0).result().sourceVelocity();
            Vec3 runtimeListenerVelocity=atomic.sourceResults().get(0).result().listenerVelocity();
            assertNear(2,runtimeSourceVelocity.x,1e-9,"source velocity x must survive atomic adapter runtime");
            assertNear(-1,runtimeSourceVelocity.y,1e-9,"source velocity y must survive atomic adapter runtime");
            assertNear(.25,runtimeSourceVelocity.z,1e-9,"source velocity z must survive atomic adapter runtime");
            assertNear(.125,runtimeListenerVelocity.x,1e-9,"listener velocity x must survive atomic adapter runtime");
            assertNear(-.25,runtimeListenerVelocity.y,1e-9,"listener velocity y must survive atomic adapter runtime");
            assertNear(.5,runtimeListenerVelocity.z,1e-9,"listener velocity z must survive atomic adapter runtime");
        } finally { atomicRuntime.close(); }

        AcousticPlatformAdapter missingCaps=new AcousticPlatformAdapter(){
            public String platformId(){return "test:no-ray-query";}
            public Capabilities capabilities(){return new Capabilities(Collections.<Capability>emptySet());}
            public dev.acoustic.api.scene.AcousticScene captureScene(SceneCaptureRequest request){return scene;}
            public ListenerSnapshot captureListener(){return new ListenerSnapshot(new Vec3(.5,.5,.5),new Vec3(0,0,1),new Vec3(0,1,0));}
            public java.util.List<SoundSourceSnapshot> captureActiveSources(){return Collections.<SoundSourceSnapshot>emptyList();}
        };
        boolean capsFailed=false;
        try { new PlatformAdapterRuntime(missingCaps,pack,"LOW",1,new SceneCaptureRequest(0,0,0,1,1,1)); }
        catch (IllegalArgumentException expected) { capsFailed=true; }
        assertTrue(capsFailed,"adapter runtime must enforce shader-pack capability negotiation before activation");
        pass("version-neutral platform adapter runtime bridge");
    }

    private static void testAdaptiveBudget() {
        AdaptiveBudgetController controller = new AdaptiveBudgetController(8.0, 0, 4, 3);
        for (int i=0;i<20;i++) controller.sample(14.0);
        assertTrue(controller.level() < 3, "sustained overload must lower quality");
        int low = controller.level();
        for (int i=0;i<80;i++) controller.sample(1.0);
        assertTrue(controller.level() > low, "sustained spare budget must restore quality");
        pass("adaptive budget controller");
    }

    private static void testStrictJsonAndManifest() {
        String json="{\"format\":1,\"spec\":\"0.1\",\"id\":\"example:test\",\"name\":\"Test\",\"requires\":[\"ray_query\"],\"optional\":[\"gpu_compute\"]}";
        ShaderPackManifest manifest=ShaderPackManifest.parse(json);
        assertTrue(manifest.required().contains(Capability.RAY_QUERY),"required capability parsed");
        assertTrue(manifest.optional().contains(Capability.GPU_COMPUTE),"optional capability parsed");
        boolean duplicateFailed=false;try{StrictJson.parse("{\"a\":null,\"a\":1}");}catch(IllegalArgumentException expected){duplicateFailed=true;}
        assertTrue(duplicateFailed,"strict JSON must reject duplicate keys");
        pass("strict shader-pack manifest parsing");
    }

    private static void testPipelineDefinition() {
        PipelineDefinition def=PipelineDefinition.parse("{\"format\":1,\"passes\":[{\"id\":\"standard.direct_path\"},{\"id\":\"standard.wave\",\"enabled\":\"${WAVE != OFF}\",\"options\":{\"quality\":\"high\"}}]}");
        assertTrue(def.passes().size()==2,"two pass definitions expected");
        assertTrue("high".equals(def.passes().get(1).options().get("quality")),"pass options parsed");
        pass("declarative pipeline definition parsing");
    }

    private static void testPackOptions() throws Exception {
        PackOptions options=PackOptions.load(new StringReader("profile.ULTRA=RAYS:4096 BOUNCES:10\nprofile.LOW=RAYS:128\nscreen=QUALITY RAYS\n"));
        assertTrue(options.profiles().size()==2,"two profiles expected");
        assertTrue("LOW".equals(options.profiles().get(0)),"profiles must be deterministic/sorted");
        assertTrue("QUALITY RAYS".equals(options.get("screen")),"arbitrary compatible option keys preserved");
        pass("OptiFine/Iris-style pack options parsing");
    }


    private static void testMaterialPack() {
        String json="{\"materials\":{\"p:soft\":{\"absorption\":[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8],\"scattering\":0.4,\"transmission\":0.1}},\"rules\":[{\"priority\":7,\"kind\":\"TAG\",\"match\":\"acoustic:fabric\",\"material\":\"p:soft\"}]}";
        MaterialPack pack=MaterialPack.parse(json);
        assertTrue(pack.materials().size()==1 && pack.rules().size()==1,"material library and rules parsed");
        assertNear(0.8,pack.materials().get("p:soft").absorption(7),1e-6,"eighth absorption band");
        pass("portable shader-pack material library");
    }

    private static void testMediumPack() {
        String json="{\"media\":{\"test:oil\":{\"density_kg_m3\":850,\"speed_m_s\":1320,\"attenuation_db_per_km\":[0.2,0.3,0.5,0.8,1.2,2,4,8]}},\"rules\":[{\"priority\":20,\"kind\":\"GLOB\",\"match\":\"mod:*oil*\",\"medium\":\"test:oil\"}]}";
        MediumPack pack=MediumPack.parse(json);
        assertTrue(pack.media().containsKey("test:oil")&&pack.rules().size()==1,"medium library and rules parsed");
        assertNear(850.0,pack.media().get("test:oil").densityKgPerCubicMeter(),1e-9,"custom medium density");
        MediumResolver resolver=new MediumResolver(pack.rules());
        MaterialDescriptor descriptor=new MaterialDescriptor("mod:heavy_oil","mod:heavy_oil#meta=0",Collections.singleton(AcousticTags.LIQUID),Collections.<String>emptySet(),false);
        assertTrue("test:oil".equals(resolver.resolve(descriptor,AcousticMedia.WATER).id()),"glob medium rule must override inferred water fallback");
        MaterialResolver materials=new MaterialResolver(Collections.<MaterialRule>emptyList(),AcousticMaterials.STONE,AcousticMaterials.AIR);
        LegacySceneCapture capture=new LegacySceneCapture(materials,resolver);
        LegacyWorldAccess world=new LegacyWorldAccess(){
            public String registryId(int x,int y,int z){return "mod:heavy_oil";}public String stateId(int x,int y,int z){return "mod:heavy_oil#meta=0";}public String materialName(int x,int y,int z){return "fluid oil";}public String soundTypeName(int x,int y,int z){return "";}public Set<String> oreDictionaryNames(int x,int y,int z){return Collections.emptySet();}public boolean solid(int x,int y,int z){return false;}public long revision(){return 9;}public dev.acoustic.mc1122.LegacyBlockSample sample(int x,int y,int z){return new dev.acoustic.mc1122.LegacyBlockSample("mod:heavy_oil","mod:heavy_oil#meta=0","fluid oil","",Collections.<String>emptySet(),false,AcousticMedia.WATER,AcousticShape.EMPTY);}
        };
        ImmutableVoxelSnapshot snap=capture.capture(world,0,0,0,1,1,1);
        assertTrue("test:oil".equals(snap.voxelAt(0,0,0).medium().id()),"scene capture must apply medium override independently of surface material");
        pass("portable shader/resource medium definitions and modded-fluid override rules");
    }


    private static void testReferencePackLoader() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));
        assertTrue("acoustic:reference".equals(pack.manifest().id()),"reference manifest id");
        assertTrue(pack.pipeline().passes().size()==10,"reference pipeline pass count");
        assertTrue(pack.options().profiles().contains("ULTRA"),"reference Ultra profile");
        assertTrue(pack.materialPacks().isEmpty(),"Reference shader keeps material database separate from shader pack");
        assertTrue(pack.mediumPacks().isEmpty(),"Reference shader keeps volume-medium database separate unless explicitly provided");
        pass("reference shader-pack end-to-end loading");
    }

    private static VoxelTestScene closedRoom(int radius) {
        VoxelTestScene.Builder b=VoxelTestScene.builder();
        for(int a=-radius;a<=radius;a++) for(int c=-radius;c<=radius;c++){
            b.solid(-radius,a,c,AcousticMaterials.STONE).solid(radius,a,c,AcousticMaterials.STONE);
            b.solid(a,-radius,c,AcousticMaterials.STONE).solid(a,radius,c,AcousticMaterials.STONE);
            b.solid(a,c,-radius,AcousticMaterials.STONE).solid(a,c,radius,AcousticMaterials.STONE);
        }
        return b.build();
    }

    private static void testEarlyReflections() throws Exception {
        MapPassContext ctx=baseContext(closedRoom(4),new Vec3(1.5,0.5,0.5),new Vec3(0.5,0.5,0.5));
        run(new EarlyReflectionPass(512,16),ctx);
        EarlyReflectionField f=ctx.require(StandardResources.EARLY_REFLECTIONS);
        assertTrue(!f.events().isEmpty(),"closed room should yield visible first-order reflections");
        assertTrue(f.events().get(0).pathDistance()>1.0,"reflection path must exceed direct distance");
        pass("source-aware early reflection estimator");
    }

    private static void testDiffraction() throws Exception {
        VoxelTestScene scene=VoxelTestScene.builder().solid(2,0,0,AcousticMaterials.STONE).build();
        MapPassContext ctx=baseContext(scene,new Vec3(0.5,0.5,0.5),new Vec3(4.5,0.5,0.5));
        run(new DirectPathPass(),ctx);run(new DiffractionPass(),ctx);
        DiffractionResult d=ctx.require(StandardResources.DIFFRACTION);
        assertTrue(d.available(),"isolated blocker should expose a diffraction edge path");
        assertTrue(d.transmission(0)>=d.transmission(7),"high frequencies should diffract no better than lows in reference model");
        pass("voxel knife-edge diffraction approximation");
    }

    private static void testWaveApproximation() throws Exception {
        MapPassContext ctx=baseContext(closedRoom(4),new Vec3(0.5,0.5,0.5),new Vec3(0.5,0.5,0.5));
        run(new WaveApproximationPass(32,500),ctx);WaveFieldResult w=ctx.require(StandardResources.WAVE_FIELD);
        assertTrue(w.sizeX()>6&&w.sizeX()<9,"room extent probe");assertTrue(!w.modesHz().isEmpty(),"closed room should have low-frequency modes");
        pass("low-frequency modal wave approximation");
    }

    private static void testLateReverb() throws Exception {
        MapPassContext ctx=baseContext(closedRoom(4),new Vec3(0.5,0.5,0.5),new Vec3(0.5,0.5,0.5));
        run(new EnvironmentRayPass(128,4,20,0.0001),ctx);run(new LateReverbPass(),ctx);LateReverb r=ctx.require(StandardResources.LATE_REVERB);
        assertTrue(r.rt60(0)>0,"late reverb must expose a positive decay estimate");
        pass("statistical late-field estimator");
    }

    private static void testDspPrimitives() {
        float[] c=FirConvolver.convolve(new float[]{1,2},new float[]{1,0.5f});assertNear(2.5,c[1],1e-6,"FIR convolution");
        float[] g=StereoSpatializer.gains(new Vec3(1,0,0),new Vec3(1,0,0));assertTrue(g[1]>g[0],"right arrival should pan right");
        pass("audio-ready reference DSP primitives");
    }

    private static void testTemporalCache() {
        TemporalResponseCache<String> c=new TemporalResponseCache<String>(0.25);Vec3 s=new Vec3(0,0,0),l=new Vec3(1,0,0);c.put(5,s,l,"ok");
        assertTrue("ok".equals(c.get(5,new Vec3(0.1,0,0),l)),"small movement may reuse response");assertTrue(c.get(6,s,l)==null,"scene revision must invalidate response");
        pass("conservative temporal response reuse");
    }

    private static void testProfileResolution() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));ResolvedProfile p=ResolvedProfile.from(pack.options(),"LOW");
        assertTrue(p.getInt("RAYS",0)==128,"profile ray count");assertTrue(!p.enabledExpression("${WAVE != OFF}"),"LOW profile disables wave pass");
        pass("shader-pack profile expression resolution");
    }

    private static void testEndToEndRuntime() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));AcousticRuntimeSession rt=new AcousticRuntimeSession(pack,"HIGH",4);try{
            AcousticRuntimeSession.FrameResult frame=rt.process(closedRoom(4),new Vec3(1.5,0.5,0.5),new Vec3(0.5,0.5,0.5));HybridResponse h=frame.response();
            assertTrue(h.direct()!=null&&h.early()!=null&&h.late()!=null&&h.wave()!=null,"hybrid response completeness");assertTrue(frame.report().elapsedNanos()>0,"runtime profiling");
        }finally{rt.close();}pass("reference shader-pack full headless runtime");
    }

    private static void testZipPackLoading() throws Exception {
        LoadedShaderPack p=new ShaderPackLoader().loadZip(java.nio.file.Paths.get("examples","reference-pack.zip"));
        assertTrue("acoustic:reference".equals(p.manifest().id()),"zip manifest");assertTrue(p.materialPacks().isEmpty(),"Reference zip keeps materials in resource packs");
        pass("safe zipped acoustic shader-pack loading");
    }

    private static void testPackValidation() throws Exception {
        LoadedShaderPack p=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));
        ShaderPackValidator v=new ShaderPackValidator();java.util.List<String> issues=v.validate(p,new Capabilities(EnumSet.allOf(Capability.class)));
        assertTrue(issues.isEmpty(),"reference pack should validate: "+issues);pass("pre-activation shader-pack validation");
    }

    private static void testPackManager() throws Exception {
        ShaderPackManager m=new ShaderPackManager();LoadedShaderPack p=m.loadAndActivate(java.nio.file.Paths.get("examples","reference-pack.zip"));assertTrue(m.active()==p,"atomic active pack publication");pass("atomic shader-pack activation");
    }

    private static void testLegacyMaterialNormalization() {
        java.util.Set<String> ores=new java.util.HashSet<String>(java.util.Arrays.asList("blockCopper"));MaterialDescriptor d=LegacyBlockDescriptor.normalize("mod:copper_block","IRON","METAL",ores,true);assertTrue(d.semanticTags().contains(AcousticTags.METAL),"legacy metal inference");pass("1.12.2 OreDictionary/material normalization seam");
    }

    private static void testLegacyCompatibilityProbe() {
        final java.util.Set<String> classes=new java.util.HashSet<String>(java.util.Arrays.asList("optifine.OptiFineClassTransformer","zone.rong.loliasm.LoliASM","zone.rong.mixinbooter.MixinBooterPlugin"));LegacyCompatibilityProbe.Result r=new LegacyCompatibilityProbe().probe(new LegacyCompatibilityProbe.ClassLookup(){public boolean present(String n){return classes.contains(n);}});assertTrue(r.optifine&&r.mixinBooter&&r.loliAsmFamily,"known legacy compatibility family detection");pass("OptiFine/MixinBooter/LoliASM compatibility probing");
    }

    private static void testWorkerTuner() {
        WorkerTuner t=new WorkerTuner(1,4);t.sample(10);t.sample(8);assertTrue(t.workers()>1,"improvement should cautiously explore workers");for(int i=0;i<4;i++)t.sample(30);assertTrue(t.workers()>=1&&t.workers()<=4,"worker bounds");pass("adaptive worker-count tuner");
    }

    private static void testDiagnostics() throws Exception {
        MapPassContext ctx=baseContext(VoxelTestScene.builder().build(),new Vec3(0,0,0),new Vec3(1,0,0));ParallelPipelineExecutor ex=new ParallelPipelineExecutor(1);try{ExecutionReport r=ex.executeProfiled(new DefaultPipeline(Collections.<Pass>singletonList(new DirectPathPass())),ctx);String text=new DiagnosticReport().put("platform","headless").execution(r).toText();assertTrue(text.contains("frame.total_ms=")&&text.contains("pass.standard.direct_path.ms="),"diagnostic timing report");}finally{ex.close();}pass("portable runtime diagnostics");
    }

    private static void testImpulseResponse() throws Exception {
        LoadedShaderPack pack=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));AcousticRuntimeSession rt=new AcousticRuntimeSession(pack,"HIGH",4);try{AcousticRuntimeSession.FrameResult f=rt.process(closedRoom(4),new Vec3(1.5,0.5,0.5),new Vec3(0.5,0.5,0.5));ImpulseResponse ir=f.impulseResponse();assertTrue(ir.sampleRate()==48000&&ir.length()==144000,"configured three-second 48k RIR");double energy=0;for(float v:ir.samples())energy+=Math.abs(v);assertTrue(energy>0,"RIR must contain direct/early/late energy");}finally{rt.close();}pass("hybrid response to audio-ready impulse response");
    }

    private static void testLegacySceneCapture() {
        MaterialRule metal=new MaterialRule(10,MaterialRule.MatchKind.TAG,AcousticTags.METAL,AcousticMaterials.METAL);MaterialResolver resolver=new MaterialResolver(java.util.Collections.singletonList(metal),AcousticMaterials.STONE,AcousticMaterials.AIR);LegacySceneCapture capture=new LegacySceneCapture(resolver);LegacyWorldAccess world=new LegacyWorldAccess(){public String registryId(int x,int y,int z){return x==1?"mod:copper_block":"minecraft:air";}public String materialName(int x,int y,int z){return x==1?"IRON":"AIR";}public String soundTypeName(int x,int y,int z){return x==1?"METAL":"";}public java.util.Set<String> oreDictionaryNames(int x,int y,int z){return x==1?new java.util.HashSet<String>(java.util.Arrays.asList("blockCopper")):java.util.Collections.<String>emptySet();}public boolean solid(int x,int y,int z){return x==1;}public long revision(){return 77;}};ImmutableVoxelSnapshot snap=capture.capture(world,0,0,0,3,1,1);assertTrue(snap.generation()==77,"legacy revision propagation");assertTrue(snap.voxelAt(1,0,0).material()==AcousticMaterials.METAL,"legacy material resolved into snapshot");assertTrue(!snap.voxelAt(0,0,0).solid(),"air remains non-solid");pass("mocked 1.12.2 immutable scene capture");
    }

    private static void testPackMaterialResolverCompiler() throws Exception {
        LoadedShaderPack p=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));MaterialResolver r=MaterialResolverCompiler.compile(p);MaterialDescriptor metal=new MaterialDescriptor("mod:machine",java.util.Collections.singleton(AcousticTags.METAL),java.util.Collections.<String>emptySet(),true);assertTrue(r.resolve(metal)==AcousticMaterials.METAL,"separate Reference shader must use built-in/material-resource fallback");MaterialDescriptor wood=new MaterialDescriptor("mod:plank",java.util.Collections.singleton(AcousticTags.WOOD),java.util.Collections.<String>emptySet(),true);assertTrue(r.resolve(wood)==AcousticMaterials.WOOD,"built-in semantic fallback remains available");pass("effective shader-pack material resolver compilation");
    }

    private static void testPassFactoryExtension() throws Exception {
        final ResourceKey<String> out=new ResourceKey<String>("extension.result",String.class);dev.acoustic.core.runtime.PassFactoryRegistry reg=dev.acoustic.core.runtime.StandardPassFactories.create();reg.register("example.extension",new dev.acoustic.core.runtime.PassFactory(){public Pass create(PipelineDefinition.PassDefinition d,dev.acoustic.core.runtime.ResolvedProfile p){return new Pass(){public String id(){return "example.extension";}public Set<ResourceKey<?>> reads(){return java.util.Collections.emptySet();}public Set<ResourceKey<?>> writes(){return java.util.Collections.<ResourceKey<?>>singleton(out);}public void execute(PassContext c){c.put(out,"ok");}};}});PipelineDefinition def=PipelineDefinition.parse("{\"format\":1,\"passes\":[{\"id\":\"example.extension\"}]}");LoadedShaderPack base=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));LoadedShaderPack custom=new LoadedShaderPack(base.manifest(),def,base.options(),base.materialPacks());DefaultPipeline pipe=new dev.acoustic.core.runtime.StandardPipelineCompiler(reg).compile(custom,"LOW");MapPassContext ctx=new MapPassContext();ParallelPipelineExecutor ex=new ParallelPipelineExecutor(1);try{ex.execute(pipe,ctx);}finally{ex.close();}assertTrue("ok".equals(ctx.require(out)),"third-party pass factory must compile and execute");pass("extension-registered custom acoustic pass");
    }

    private static void testPackUiModel() throws Exception {LoadedShaderPack p=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));PackUiModel ui=PackUiModel.from(p.options());assertTrue(ui.screenTokens().contains("RAYS")&&ui.sliders().contains("BOUNCES")&&ui.profiles().contains("ULTRA"),"pack UI declarations");pass("loader-neutral shader option UI model");}

    private static void testAdaptiveRuntimeProfileSwitch() throws Exception {LoadedShaderPack p=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));AdaptiveAcousticRuntime rt=new AdaptiveAcousticRuntime(p,0.000001,2,"ULTRA");try{String before=rt.currentProfile();rt.process(closedRoom(3),new Vec3(1.5,.5,.5),new Vec3(.5,.5,.5));assertTrue(!before.equals(rt.currentProfile()),"severe budget overload should downgrade profile");}finally{rt.close();}pass("budget-driven shader profile adaptation");}

    private static void testOptionalWaveFallback() throws Exception {LoadedShaderPack p=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));AcousticRuntimeSession rt=new AcousticRuntimeSession(p,"LOW",2);try{AcousticRuntimeSession.FrameResult f=rt.process(closedRoom(3),new Vec3(1.5,.5,.5),new Vec3(.5,.5,.5));assertTrue(!f.response().hasWaveField(),"LOW profile must remain valid with wave pass disabled");assertTrue(f.impulseResponse().length()>0,"hybrid output still produced without optional wave field");}finally{rt.close();}pass("optional pipeline resource fallback");}

    private static void testWavExport() throws Exception {float[] samples=new float[]{0f,0.25f,-0.5f,1f};ImpulseResponse ir=new ImpulseResponse(48000,samples);java.nio.file.Path p=java.nio.file.Files.createTempFile("acoustic-rir-",".wav");try{WavWriter.writeMono16(p,ir);byte[] b=java.nio.file.Files.readAllBytes(p);assertTrue(b.length==44+samples.length*2,"PCM16 WAV size");assertTrue(b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='A'&&b[10]=='V'&&b[11]=='E',"valid RIFF/WAVE header");}finally{java.nio.file.Files.deleteIfExists(p);}pass("diagnostic PCM16 RIR WAV export");}

    private static void testWorkerDeterminism() throws Exception {LoadedShaderPack p=new ShaderPackLoader().loadDirectory(java.nio.file.Paths.get("examples","reference-pack"));ImpulseResponse a,b;AcousticRuntimeSession one=new AcousticRuntimeSession(p,"HIGH",1),four=new AcousticRuntimeSession(p,"HIGH",4);try{a=one.process(closedRoom(4),new Vec3(1.5,.5,.5),new Vec3(.5,.5,.5)).impulseResponse();b=four.process(closedRoom(4),new Vec3(1.5,.5,.5),new Vec3(.5,.5,.5)).impulseResponse();}finally{one.close();four.close();}assertTrue(java.util.Arrays.equals(a.samples(),b.samples()),"worker count must not change deterministic reference RIR");pass("deterministic results across worker counts");}

    private static void testSchedulerFailureCancellation() throws Exception {final java.util.concurrent.CountDownLatch slowStarted=new java.util.concurrent.CountDownLatch(1);final java.util.concurrent.CountDownLatch interrupted=new java.util.concurrent.CountDownLatch(1);Pass fail=new Pass(){public String id(){return "fail";}public Set<ResourceKey<?>> reads(){return java.util.Collections.emptySet();}public Set<ResourceKey<?>> writes(){return java.util.Collections.emptySet();}public void execute(PassContext c)throws Exception{if(!slowStarted.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new Exception("slow did not start");throw new Exception("expected");}};Pass slow=new Pass(){public String id(){return "slow";}public Set<ResourceKey<?>> reads(){return java.util.Collections.emptySet();}public Set<ResourceKey<?>> writes(){return java.util.Collections.emptySet();}public void execute(PassContext c)throws Exception{slowStarted.countDown();try{Thread.sleep(30000);}catch(InterruptedException e){interrupted.countDown();throw e;}}};ParallelPipelineExecutor ex=new ParallelPipelineExecutor(2);boolean failed=false;try{ex.execute(new DefaultPipeline(java.util.Arrays.asList(fail,slow)),new MapPassContext());}catch(Exception expected){failed=true;}finally{ex.close();}assertTrue(failed,"failing pass must abort level");assertTrue(interrupted.await(2,java.util.concurrent.TimeUnit.SECONDS),"independent outstanding work should be cancelled/interrupted");pass("fail-fast scheduler cancellation");}

    private static void testPartitionedConvolution() {float[] x=new float[700],h=new float[333];for(int i=0;i<x.length;i++)x[i]=(float)Math.sin(i*.071);for(int i=0;i<h.length;i++)h[i]=(float)(Math.exp(-i/80.0)*Math.cos(i*.17)*.05);float[] expected=FirConvolver.convolve(x,h);PartitionedConvolver c=new PartitionedConvolver(h,128);float[] actual=c.processAll(x);assertTrue(actual.length>=expected.length,"partitioned convolution tail length");double max=0;for(int i=0;i<expected.length;i++)max=Math.max(max,Math.abs(expected[i]-actual[i]));assertTrue(max<1e-4,"FFT partitioned convolution error="+max);pass("FFT uniform partitioned convolution");}

    private static void testPcmCodec() {
        byte[] pcm16=new byte[]{0,0,(byte)0xff,0x7f,0, (byte)0x80};
        float[] decoded=PcmCodec.decodeMono(pcm16,16,true,false);
        assertNear(0,decoded[0],1e-6,"PCM16 zero");
        assertTrue(decoded[1]>.99f&&decoded[2]<=-1f,"PCM16 extrema");
        byte[] pcm8=new byte[]{0,(byte)128,(byte)255};
        float[] unsigned=PcmCodec.decodeMono(pcm8,8,false,false);
        assertTrue(unsigned[0]<=-1f&&Math.abs(unsigned[1])<1e-6&&unsigned[2]>.98f,"unsigned PCM8 decode");
        byte[] stereo=PcmCodec.encodeStereo16(new float[]{1f,-1f},new float[]{0f,.5f},false);
        assertTrue(stereo.length==8,"stereo PCM16 frame size");
        pass("strict PCM8/PCM16 codec");
    }

    private static void testFoaWetRendering() {
        int rate=8000; float[] mono=new float[256]; mono[0]=1f; mono[80]=.25f;
        ImpulseResponse rir=new ImpulseResponse(rate,mono);
        float[] e=new float[dev.acoustic.api.material.FrequencyBands.COUNT]; java.util.Arrays.fill(e,.4f);
        EarlyReflection rightEvent=new EarlyReflection(3.43,.01,new Vec3(1,0,0),new Vec3(0,0,0),e);
        EarlyReflectionField field=new EarlyReflectionField(java.util.Collections.singletonList(rightEvent));
        FoaImpulseResponse foa=FoaRenderer.encode(rir,field);
        float[][] stereo=FoaRenderer.decodeStereo(foa,new Vec3(0,0,1),new Vec3(0,1,0));
        assertTrue(stereo[1][80]>stereo[0][80],"right-arriving reflection must decode stronger in right channel");
        byte[] dry=new byte[128*2]; dry[0]=(byte)0xff; dry[1]=0x7f;
        float[] hiRate=new float[1536]; hiRate[0]=1f; hiRate[480]=.25f;
        ImpulseResponse hiRir=new ImpulseResponse(48000,hiRate);
        SoftwareWetPcmRenderer.Rendered rendered=new SoftwareWetPcmRenderer(32,.1).renderMono16(dry,rate,hiRir,field,new Vec3(0,0,1),.5f);
        assertTrue(rendered.sampleRate==rate,"wet renderer resamples RIR to source PCM rate");
        assertTrue(rendered.pcmStereo16.length==rendered.frames*4,"wet renderer emits interleaved stereo PCM16");
        boolean nonzero=false; for(int i=4;i<rendered.pcmStereo16.length;i++) if(rendered.pcmStereo16[i]!=0){nonzero=true;break;}
        assertTrue(nonzero,"wet renderer preserves delayed reflected energy after direct removal");
        float[] w=new float[384],zero=new float[384]; w[24]=.05f; w[120]=1f;
        FoaImpulseResponse tagged=new FoaImpulseResponse(48000,w,zero,zero,zero,24);
        SoftwareWetPcmRenderer.Rendered exact=new SoftwareWetPcmRenderer(16,.1).renderMono16(dry,rate,tagged,new Vec3(0,0,1),1f);
        int directByte=4*4, reflectedByte=20*4;
        int directL=(short)((exact.pcmStereo16[directByte]&255)|((exact.pcmStereo16[directByte+1]&255)<<8));
        int reflectedL=(short)((exact.pcmStereo16[reflectedByte]&255)|((exact.pcmStereo16[reflectedByte+1]&255)<<8));
        assertTrue(Math.abs(directL)<=1,"tagged direct arrival must be removed exactly, got "+directL);
        assertTrue(Math.abs(reflectedL)>1000,"strong later reflection must survive exact direct removal, got "+reflectedL);
        pass("FOA ACN/SN3D directionality + exact-direct software wet convolution");
    }

    private static void testSourceBudgeting() {java.util.List<SourceCandidate> sources=java.util.Arrays.asList(new SourceCandidate(1,new Vec3(1,0,0),1,1),new SourceCandidate(2,new Vec3(30,0,0),1,1),new SourceCandidate(3,new Vec3(5,0,0),.2,1),new SourceCandidate(4,new Vec3(10,0,0),1,4));SourceBudgetAllocator.Allocation a=new SourceBudgetAllocator().allocate(sources,new Vec3(0,0,0),1,1);assertTrue(a.high().get(0).id()==1,"near loud source should win high quality slot");assertTrue(a.medium().get(0).id()==4,"explicit importance should affect ranking");assertTrue(a.low().size()==2,"remaining sources use cheap tier");pass("perceptual multi-source quality budgeting");}

    private static MapPassContext baseContext(dev.acoustic.api.scene.AcousticScene scene, Vec3 source, Vec3 listener) {
        MapPassContext ctx = new MapPassContext();
        ctx.put(StandardResources.SCENE, scene);
        ctx.put(StandardResources.SOURCE_POSITION, source);
        ctx.put(StandardResources.LISTENER_POSITION, listener);
        return ctx;
    }

    private static void run(Pass pass, MapPassContext ctx) throws Exception {
        ParallelPipelineExecutor executor = new ParallelPipelineExecutor(2);
        try { executor.execute(new DefaultPipeline(Collections.singletonList(pass)), ctx); }
        finally { executor.close(); }
    }

    private static void pass(String name) { passed++; System.out.println("[PASS] " + name); }
    private static void assertTrue(boolean value, String message) { if(!value) throw new AssertionError(message); }
    private static void assertNear(double expected,double actual,double epsilon,String message){ if(Math.abs(expected-actual)>epsilon)throw new AssertionError(message+": expected "+expected+", got "+actual); }
}
