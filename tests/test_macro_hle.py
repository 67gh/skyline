#!/usr/bin/env python3
"""Host unit harness for the production macro HLE functions (no Vulkan/Android).
Extracts the unchanged function bodies and supplies recording engine/argument
adapters. This does not validate driver behavior or compile the Android app.
Run: python3 tests/test_macro_hle.py
"""
from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/cpp/skyline/soc/gm20b/macro/macro_state.cpp').read_text()
body = source[source.index('namespace skyline::soc::gm20b {'):source.index('        struct HleFunctionInfo')]
pre = r'''
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <functional>
#include <span>
#include <vector>
using u32=uint32_t; using u8=uint8_t;
namespace ranges { template<class T,class F> bool any_of(T t,F f) {return std::any_of(t.begin(),t.end(),f);} }
template<class T> struct span : std::span<T> {
 using std::span<T>::span;
 span(std::span<T> s):std::span<T>(s){}
 span subspan(size_t n)const{return span(std::span<T>::subspan(n));}
 template<class U> span<U> cast()const{return {reinterpret_cast<U*>(this->data()),this->size_bytes()/sizeof(U)};}
};
template<class T> span(T*,size_t)->span<T>;
namespace skyline::soc::gm20b {
struct GpfifoArgument {u32 argument{};u32* argumentPtr{};bool dirty{};u32 operator*()const{return argumentPtr?*argumentPtr:argument;}};
namespace engine {
namespace maxwell3d::type {enum class DrawTopology:u32 {Triangles=1,Quads=2,QuadStrip=3,Polygon=4};}
struct MacroEngineBase {
 u32 mask=~u32{};int direct=0,indirect=0,nonindexed=0;std::array<u32,6> values{};
 u32 ReadMethodFromMacro(u32 m){assert(m==0xD1B);return mask;}
 void DrawInstanced(u32 t,u32 c,u32 n,u32 f,u32 b){++nonindexed;values={t,c,n,f,b,0};}
 void DrawIndexedInstanced(u32 t,u32 c,u32 n,u32 v,u32 f,u32 b){++direct;values={t,c,n,v,f,b};}
 void DrawIndexedIndirect(u32 t,span<u8> s,u32 n,u32 stride){++indirect;assert(s.size()==20&&n==1&&stride==0);values[0]=t;}
};}
}
'''
tests = r'''
using namespace skyline::soc::gm20b;
int main(){
 for(int scenario=0;scenario<10;++scenario){
  engine::MacroEngineBase e;int flushes=0;
  u32 data[6]={1,10,7,3,4,5},separate=99;
  std::vector<GpfifoArgument> a;
  for(auto &x:data)a.push_back({0,&x,false});
  if(scenario!=0) a[1].dirty=true;
  if(scenario==2){e.mask=3;}
  if(scenario==3){a[3].argumentPtr=&separate;}
  if(scenario==4){a[1]={10,nullptr,true};}
  if(scenario==5){a[0].dirty=true;}
  if(scenario==6){a[1].dirty=false;a[2].dirty=true;}
  if(scenario==7){data[0]=2;}
  if(scenario==8){a.resize(3);}
  if(scenario==9){a.push_back({0,nullptr,false});}
  auto flush=[&]{++flushes;if(scenario==5)data[0]=2;if(scenario==2)data[2]=6;};
  bool ok=macro_hle::DrawInstancedIndexedIndirect(0,{a.data(),a.size()},&e,flush);
  if(scenario>=8){assert(!ok&&e.direct==0&&e.indirect==0&&flushes==0);continue;}
  assert(ok);
  if(scenario==1||scenario==6){assert(e.indirect==1&&e.direct==0&&flushes==0);}
  else{assert(e.direct==1&&e.indirect==0);assert(flushes==(scenario==0?0:1));}
  if(scenario==2)assert(e.values[2]==2);
  if(scenario==3)assert(e.values[4]==99);
  if(scenario==5)assert(e.values[0]==2);
 }
 engine::MacroEngineBase e;int flushes=0;GpfifoArgument a[5]={{1},{10},{7},{3},{4}};a[2].dirty=true;
 assert(macro_hle::DrawInstanced(0,{a,5},&e,[&]{++flushes;a[2].argument=2;}));
 assert(flushes==1&&e.nonindexed==1&&e.values[2]==2);
 assert(!macro_hle::DrawInstanced(0,{a,4},&e,[]{}));
}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'test.cpp').write_text(pre+body+'\n}}\n'+tests)
 subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wno-unused-parameter','-fsanitize=undefined','-fno-sanitize-recover=all',str(p/'test.cpp'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
print('PASS: 12 macro HLE scenarios; undefined-behavior sanitizer enabled')

# Execute the production pushbuffer synchronization/copy block with a simulated
# GPU write made visible by Submit. This catches copying before synchronization.
gpfifo=(root/'app/src/main/cpp/skyline/soc/gm20b/gpfifo.cpp').read_text()
block=gpfifo[gpfifo.index('        auto pushBufferMappedRanges{'):gpfifo.index('        // There will be at least one entry here')]
setup=r'''
struct Range {u32* p;size_t n;template<class T> span<T> cast(){return {reinterpret_cast<T*>(p),n*sizeof(u32)/sizeof(T)};}};
struct MockMmu {
 u32 words[4]={1,2,3,4};bool split=false;
 std::vector<Range> TranslateRange(int,size_t){if(split)return {{words,2},{words+2,2}};return {{words,4}};}
 template<class T> void Read(std::vector<T>& out,int){std::copy(words,words+4,out.begin());}
};
struct MockAddressSpace {MockMmu gmmu;};
struct MockDirty {bool dirty=false;bool Intersect(Range){return dirty;}};
struct MockUsage {MockDirty dirtyIntervals;};
struct MockExecutor {MockUsage usageTracker;MockMmu* mmu;int submits=0;
 void Submit(std::nullptr_t,bool wait){assert(wait);++submits;mmu->words[0]=99;usageTracker.dirtyIntervals.dirty=false;}};
struct MockContext {MockAddressSpace* asCtx;MockExecutor executor;};
struct Entry {size_t size=4;int Address(){return 0;}};
int main(){
 for(int scenario=0;scenario<5;++scenario){
  MockAddressSpace space;space.gmmu.split=scenario<3;
  MockContext channelCtx{&space,{{{scenario!=2}},&space.gmmu}};
  bool skipDirtyFlushes=scenario!=1&&scenario!=4;
  Entry gpEntry;std::vector<u32> pushBufferData;
'''
checks=r'''
  if(scenario==0||scenario==1){assert(pushBufferCopied&&!pushbufferDirty&&pushBuffer[0]==99&&channelCtx.executor.submits==1);}
  if(scenario==2){assert(pushBufferCopied&&!pushbufferDirty&&pushBuffer[0]==1&&channelCtx.executor.submits==0);}
  if(scenario==3){assert(!pushBufferCopied&&pushbufferDirty&&channelCtx.executor.submits==0);}
  if(scenario==4){assert(!pushBufferCopied&&!pushbufferDirty&&pushBuffer[0]==99&&channelCtx.executor.submits==1);}
 }
}
'''
# Add deduction for the project's vector-backed span constructor.
pre2=pre+'\ntemplate<class T> span(std::vector<T>&)->span<T>;\n'
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'test.cpp').write_text(pre2+setup+block+checks)
 subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-fsanitize=undefined','-fno-sanitize-recover=all',str(p/'test.cpp'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
print('PASS: 5 production GPFIFO copy/synchronization scenarios')
