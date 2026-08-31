extern "C" __global__ void acoustic_fdtd(
    const float* prev,const float* cur,float* next,
    const int* solid,const float* refl,float* response,
    int nx,int ny,int nz,int listener,float lambda,float damping,int step)
{
    int i=(int)(blockIdx.x*blockDim.x+threadIdx.x);
    int cells=nx*ny*nz;
    if(i>=cells)return;
    int plane=nx*ny;
    int z=i/plane;
    int rem=i-z*plane;
    int y=rem/nx;
    int x=rem-y*nx;
    if(i==listener)response[step]=cur[i];
    if(x==0||y==0||z==0||x==nx-1||y==ny-1||z==nz-1||solid[i]!=0){next[i]=0.0f;return;}
    float center=cur[i];
    int n0=i-1,n1=i+1,n2=i-nx,n3=i+nx,n4=i-plane,n5=i+plane;
    float v0=solid[n0]!=0?center*refl[n0]:cur[n0];
    float v1=solid[n1]!=0?center*refl[n1]:cur[n1];
    float v2=solid[n2]!=0?center*refl[n2]:cur[n2];
    float v3=solid[n3]!=0?center*refl[n3]:cur[n3];
    float v4=solid[n4]!=0?center*refl[n4]:cur[n4];
    float v5=solid[n5]!=0?center*refl[n5]:cur[n5];
    float lap=v0+v1+v2+v3+v4+v5-6.0f*center;
    float value=(2.0f-damping)*center-(1.0f-damping)*prev[i]+lambda*lap;
    if(value>8.0f)value=8.0f;else if(value<-8.0f)value=-8.0f;
    next[i]=value;
}
