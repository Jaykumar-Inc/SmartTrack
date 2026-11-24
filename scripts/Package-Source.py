"""Create a shareable source ZIP without credentials, runtime databases or build caches."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import os

root=Path(__file__).resolve().parents[1]
output=root/'deliverables'
output.mkdir(exist_ok=True)
excluded={'.git','.idea','.gradle','.pio','target','build','data','logs','tools','deliverables','__pycache__','.local'}
archive=output/'SmartTrack-source.zip'
with ZipFile(archive,'w',ZIP_DEFLATED) as bundle:
 for directory,subdirectories,files in os.walk(root):
  subdirectories[:]=sorted(name for name in subdirectories if name not in excluded)
  for name in sorted(files):
   file=Path(directory)/name
   relative=file.relative_to(root)
   if file.name in {'config.h','smarttrack_config.h','local.properties','.env'} or file.suffix in {'.log','.iml','.csv','.jks','.keystore','.exe','.pdb','.obj','.wasm'}:continue
   bundle.write(file,Path('SmartTrack')/relative)
print(f'Created {archive.name} ({archive.stat().st_size:,} bytes)')
checksums=[]
for artifact in sorted(output.iterdir()):
 if artifact.is_file() and artifact.name!='SHA256SUMS.txt':
  with artifact.open('rb') as stream:
   checksum=hashlib.file_digest(stream,'sha256').hexdigest()
  checksums.append(f'{checksum} {artifact.name}')
(output/'SHA256SUMS.txt').write_text('\n'.join(checksums)+'\n',encoding='utf-8')
print('\n'.join(checksums))
