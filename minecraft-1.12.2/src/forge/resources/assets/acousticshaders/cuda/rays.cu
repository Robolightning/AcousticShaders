struct AcV3 { float x,y,z; };
__device__ __forceinline__ AcV3 ac_v3(float x,float y,float z){AcV3 v={x,y,z};return v;}
__device__ __forceinline__ float ac_dot(AcV3 a,AcV3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
__device__ __forceinline__ AcV3 ac_add(AcV3 a,AcV3 b){return ac_v3(a.x+b.x,a.y+b.y,a.z+b.z);}
__device__ __forceinline__ AcV3 ac_mul(AcV3 a,float s){return ac_v3(a.x*s,a.y*s,a.z*s);}
__device__ __forceinline__ AcV3 ac_norm(AcV3 a){float q=ac_dot(a,a);if(q<=1.0e-20f)return ac_v3(0.0f,1.0f,0.0f);float inv=1.0f/sqrtf(q);return ac_mul(a,inv);}
__device__ __forceinline__ int ac_sign(float v){return v>0.0f?1:(v<0.0f?-1:0);}
__device__ __forceinline__ int ac_floori(float v){int i=(int)v;return ((float)i>v)?i-1:i;}
__device__ __forceinline__ int ac_idx(int x,int y,int z,int minx,int miny,int minz,int nx,int ny,int nz){int lx=x-minx,ly=y-miny,lz=z-minz;if(lx<0||ly<0||lz<0||lx>=nx||ly>=ny||lz>=nz)return -1;return (ly*nz+lz)*nx+lx;}
__device__ __forceinline__ float ac_abs(float v){return v<0.0f?-v:v;}
__device__ __forceinline__ float ac_min(float a,float b){return a<b?a:b;}
__device__ __forceinline__ float ac_max(float a,float b){return a>b?a:b;}
__device__ __forceinline__ float ac_boundary(float o,int cell,int step,float d){if(step==0)return 3.402823466e+38F;float edge=step>0?(float)(cell+1):(float)cell;return (edge-o)/d;}
__device__ __forceinline__ int ac_box_hit(AcV3 o,AcV3 d,AcV3 mn,AcV3 mx,float minT,float maxT,float* outT,AcV3* outN){
    const float INF=3.402823466e+38F,eps=1.0e-7f;float nx=-INF,fx=INF,ny=-INF,fy=INF,nz=-INF,fz=INF;
    if(ac_abs(d.x)<eps){if(o.x<mn.x-eps||o.x>mx.x+eps)return 0;}else{float a=(mn.x-o.x)/d.x,b=(mx.x-o.x)/d.x;nx=ac_min(a,b);fx=ac_max(a,b);}
    if(ac_abs(d.y)<eps){if(o.y<mn.y-eps||o.y>mx.y+eps)return 0;}else{float a=(mn.y-o.y)/d.y,b=(mx.y-o.y)/d.y;ny=ac_min(a,b);fy=ac_max(a,b);}
    if(ac_abs(d.z)<eps){if(o.z<mn.z-eps||o.z>mx.z+eps)return 0;}else{float a=(mn.z-o.z)/d.z,b=(mx.z-o.z)/d.z;nz=ac_min(a,b);fz=ac_max(a,b);}
    float near=ac_max(nx,ac_max(ny,nz)),far=ac_min(fx,ac_min(fy,fz));if(far+eps<near||far+eps<minT||near-eps>maxT)return 0;AcV3 n=ac_v3(0,0,0);
    if(near>=minT-eps){if(nx>=ny&&nx>=nz)n=ac_v3(d.x>0?-1.0f:1.0f,0,0);else if(ny>=nz)n=ac_v3(0,d.y>0?-1.0f:1.0f,0);else n=ac_v3(0,0,d.z>0?-1.0f:1.0f);}near=ac_max(near,minT);far=ac_min(far,maxT);if(far+eps<near)return 0;*outT=near;*outN=n;return 1;
}
__device__ __forceinline__ int ac_hit(const int* shapeOff,const int* shapeCount,const float* boxes,int minx,int miny,int minz,int nx,int ny,int nz,AcV3 origin,AcV3 dir,float maxdist,int* hitidx,float* hitdist,AcV3* normal){
    const float INF=3.402823466e+38F;int x=ac_floori(origin.x),y=ac_floori(origin.y),z=ac_floori(origin.z),sx=ac_sign(dir.x),sy=ac_sign(dir.y),sz=ac_sign(dir.z);
    float dx=sx==0?INF:ac_abs(1.0f/dir.x),dy=sy==0?INF:ac_abs(1.0f/dir.y),dz=sz==0?INF:ac_abs(1.0f/dir.z),tx=ac_boundary(origin.x,x,sx,dir.x),ty=ac_boundary(origin.y,y,sy,dir.y),tz=ac_boundary(origin.z,z,sz,dir.z),travel=0.0f;
    for(int guard=0;guard<4096;guard++){int idx=ac_idx(x,y,z,minx,miny,minz,nx,ny,nz);if(idx<0)return 0;float cellExit=ac_min(maxdist,ac_min(tx,ac_min(ty,tz))),best=INF;AcV3 bestN=ac_v3(0,0,0);int off=shapeOff[idx],count=shapeCount[idx];
        for(int q=0;q<count;q++){int bi=(off+q)*6;AcV3 mn=ac_v3((float)x+boxes[bi],(float)y+boxes[bi+1],(float)z+boxes[bi+2]),mx=ac_v3((float)x+boxes[bi+3],(float)y+boxes[bi+4],(float)z+boxes[bi+5]),n;float t;if(ac_box_hit(origin,dir,mn,mx,travel,cellExit,&t,&n)&&t<best){best=t;bestN=n;}}
        if(best<INF){*hitidx=idx;*hitdist=best;*normal=bestN;return 1;}if(cellExit>=maxdist)return 0;if(tx<=ty&&tx<=tz){travel=tx;tx+=dx;x+=sx;}else if(ty<=tz){travel=ty;ty+=dy;y+=sy;}else{travel=tz;tz+=dz;z+=sz;}}
    return 0;
}
extern "C" __global__ void acoustic_rays(
    const int* shapeOff,const int* shapeCount,const float* boxes,const float* scatter,const float* refl,float* paths,float* energies,
    const float* initialDirs,const float* diffuseDirs,
    int minx,int miny,int minz,int nx,int ny,int nz,float lx,float ly,float lz,
    int rays,int bounces,float maxdist,float minenergy)
{
    int ray=(int)(blockIdx.x*blockDim.x+threadIdx.x);if(ray>=rays)return;
    AcV3 initial=ac_v3(initialDirs[ray*3],initialDirs[ray*3+1],initialDirs[ray*3+2]);AcV3 dir=initial,origin=ac_v3(lx,ly,lz);float energy[8];
    #pragma unroll
    for(int band=0;band<8;band++)energy[band]=1.0f;
    float total=0.0f;
    for(int bounce=1;bounce<=bounces;bounce++){
        int hi=-1;float hd=0.0f;AcV3 n;if(!ac_hit(shapeOff,shapeCount,boxes,minx,miny,minz,nx,ny,nz,origin,dir,maxdist,&hi,&hd,&n))return;
        total+=hd;int slot=ray*bounces+(bounce-1),alive=0;
        #pragma unroll
        for(int band=0;band<8;band++){energy[band]*=refl[hi*8+band];energies[slot*8+band]=energy[band];if(energy[band]>=minenergy)alive=1;}
        paths[slot]=total;if(!alive)return;AcV3 hitpos=ac_add(origin,ac_mul(dir,hd));float dn=ac_dot(dir,n);AcV3 spec=ac_norm(ac_add(dir,ac_mul(n,-2.0f*dn)));float s=scatter[hi];
        if(s>0.0f){int dslot=slot*3;AcV3 diffuse=ac_v3(diffuseDirs[dslot],diffuseDirs[dslot+1],diffuseDirs[dslot+2]);if(ac_dot(diffuse,n)<0.0f)diffuse=ac_mul(diffuse,-1.0f);dir=ac_norm(ac_add(ac_mul(spec,1.0f-s),ac_mul(diffuse,s)));}else dir=spec;origin=ac_add(hitpos,ac_mul(n,1.0e-4f));
    }
}
