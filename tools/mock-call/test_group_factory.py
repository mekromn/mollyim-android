#!/usr/bin/env python3
"""Compile the actual patched group-factory blocks with checked exceptions."""
from pathlib import Path
import os,re,shutil,subprocess,tempfile,unittest
import patch_ringrtc
ROOT=Path(__file__).resolve().parents[2]
class GroupFactoryTests(unittest.TestCase):
 def test_both_group_apis_retain_null_on_resource_failure(self):
  ring=Path(os.environ['RINGRTC_SOURCE'])
  with tempfile.TemporaryDirectory() as td:
   manager=(ring/'src/android/api/org/signal/ringrtc/CallManager.java').read_text()
   manager=getattr(patch_ringrtc,'patch_group_factories',lambda value:value)(manager)
   blocks=[]
   for method in ('createGroupCall','createCallLinkCall'):
    body=manager[manager.index('public GroupCall '+method+'('):]
    block=body[body.index('    if (this.groupFactory == null) {'):body.index('    GroupCall groupCall =')]
    blocks.append(block)
   java='''class CheckedFactory {
static final Object TAG = null;
static class CallException extends Exception {}
static class PeerConnectionFactory {}
static class Log {static void e(Object a,Object b){} static void w(Object a,Object b,Throwable t){}}
PeerConnectionFactory groupFactory;
PeerConnectionFactory createPeerConnectionFactory(Object a,Object b) throws CallException {throw new CallException();}
'''
   for i,b in enumerate(blocks):java+='Object create'+str(i)+'(Object audioConfig) {\n'+b+'return groupFactory;\n}\n'
   java+='public static void main(String[] args){CheckedFactory c=new CheckedFactory();if(c.create0(null)!=null||c.create1(null)!=null)throw new AssertionError("Failure must return null");}\n}\n'
   source=Path(td)/'CheckedFactory.java';source.write_text(java)
   result=subprocess.run(['javac','-Xlint:all',str(source)],capture_output=True,text=True)
   self.assertEqual(result.returncode,0,result.stderr)
   subprocess.run(['java','-cp',td,'CheckedFactory'],check=True)
if __name__=='__main__':unittest.main()
