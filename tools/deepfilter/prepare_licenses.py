#!/usr/bin/env python3
"""Aggregate notices from the exact locked inference dependency sources in CI."""
import argparse,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('metadata',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
metadata=json.loads(a.metadata.read_text());parts=['Molly Audio DeepFilterNet runtime: locked dependency notices\n'];records=[]
for package in sorted(metadata['packages'],key=lambda p:(p['name'],p['version'])):
 record={k:package.get(k) for k in ('name','version','license','repository','authors')};records.append(record)
 parts.append('\n\n=== '+package['name']+' '+package['version']+' ===\n'+json.dumps(record,ensure_ascii=False,indent=2)+'\n')
 directory=Path(package['manifest_path']).parent;found=[]
 for parent in [directory,*list(directory.parents)[:3]]:
  if parent.name in ('registry','git','src','.cargo'):break
  for f in sorted(parent.iterdir()):
   if f.is_file() and f.name.upper().startswith(('LICENSE','LICENCE','COPYING','NOTICE')) and f.stat().st_size<256000:
    found.append(f)
  if found:break
 for file in found:parts.append('\n'+file.name+'\n'+file.read_text(errors='replace'))
a.output.mkdir(parents=True,exist_ok=True)
(a.output/'notices.txt').write_text('\n'.join(parts),encoding='utf-8')
(a.output/'runtime-dependencies.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
print('Packaged notices and metadata for',len(records),'locked packages (including build/test dependencies).')
