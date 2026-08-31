package dev.acoustic.tests;

import dev.acoustic.api.material.AcousticMaterials;
import dev.acoustic.api.math.Vec3;
import dev.acoustic.core.passes.EnvironmentRayPass;
import dev.acoustic.core.passes.StandardResources;
import dev.acoustic.core.pipeline.DefaultPipeline;
import dev.acoustic.core.pipeline.ExecutionReport;
import dev.acoustic.core.pipeline.MapPassContext;
import dev.acoustic.core.pipeline.ParallelPipelineExecutor;
import dev.acoustic.testkit.VoxelTestScene;
import java.util.Collections;

/** Simple repeatable microbenchmark; not a scientific benchmark and never used as a correctness gate. */
public final class RayBenchmark {
    public static void main(String[] args) throws Exception {
        int rays=args.length>0?Integer.parseInt(args[0]):32768;
        VoxelTestScene scene=box(12);
        for(int workers:new int[]{1,2,4,8}){
            double best=Double.POSITIVE_INFINITY;
            for(int round=0;round<4;round++){
                MapPassContext ctx=new MapPassContext();ctx.put(StandardResources.SCENE,scene);ctx.put(StandardResources.LISTENER_POSITION,new Vec3(0.5,0.5,0.5));
                ParallelPipelineExecutor executor=new ParallelPipelineExecutor(workers);
                try{ExecutionReport report=executor.executeProfiled(new DefaultPipeline(Collections.singletonList(new EnvironmentRayPass(rays,4,100.0,0.0001))),ctx);if(round>0)best=Math.min(best,report.elapsedMillis());}finally{executor.close();}
            }
            System.out.printf("workers=%d rays=%d best_ms=%.3f Mray/s=%.3f%n",workers,rays,best,(rays/best)/1000.0);
        }
    }
    private static VoxelTestScene box(int radius){VoxelTestScene.Builder b=VoxelTestScene.builder();for(int a=-radius;a<=radius;a++)for(int c=-radius;c<=radius;c++){b.solid(-radius,a,c,AcousticMaterials.STONE).solid(radius,a,c,AcousticMaterials.STONE);b.solid(a,-radius,c,AcousticMaterials.STONE).solid(a,radius,c,AcousticMaterials.STONE);b.solid(a,c,-radius,AcousticMaterials.STONE).solid(a,c,radius,AcousticMaterials.STONE);}return b.build();}
}
