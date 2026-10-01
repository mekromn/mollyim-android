#!/usr/bin/env python3
"""Verify the actual produced native ELF, not a filename or build flag."""
import hashlib,json,struct,subprocess,sys
from pathlib import Path

def verify(path:Path)->dict:
 data=path.read_bytes()
 if data[:6]!=b'\x7fELF\x02\x01': raise ValueError('Expected 64-bit little-endian AArch64 ELF')
 if struct.unpack_from('<H',data,18)[0]!=183: raise ValueError('Expected AArch64 runtime; host library is not Android proof')
 phoff=struct.unpack_from('<Q',data,32)[0]; entsize,num=struct.unpack_from('<HH',data,54)
 if entsize!=56 or num<1: raise ValueError('Invalid ELF program headers')
 loads=[]
 for i in range(num):
  typ,flags,off,addr,phys,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,phoff+entsize*i)
  if typ==1:
   if align<16384 or (addr-off)%16384:raise ValueError('ELF is not aligned for 16 KB pages')
   loads.append({'offset':off,'address':addr,'alignment':align})
 if not loads: raise ValueError('No loadable segments')
 text=subprocess.check_output(['readelf','--dyn-syms','--wide',str(path)],text=True)
 names={s.split()[-1] for s in text.splitlines() if len(s.split())>=8 and s.split()[6]!='UND'}
 expected={'molly_df_'+s for s in ['abi_version','create','configure','process','reset','destroy']}
 if not expected<=names:raise ValueError('Missing checked runtime exports')
 return {'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'machine':'AArch64','load_segments':loads,'exports':sorted(expected)}
if __name__=='__main__':
 try:print(json.dumps(verify(Path(sys.argv[1])),indent=2))
 except (ValueError,struct.error) as e:sys.exit(str(e))
