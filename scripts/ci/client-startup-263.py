"""Headless, no-world client startup check for the 26.3 compatibility port."""
import os, pathlib, re, signal, subprocess, threading, time
out=pathlib.Path('client-startup-evidence');out.mkdir(exist_ok=True)
lines=[]
p=subprocess.Popen(['xvfb-run','-a','./gradlew','runClient','--no-daemon'],cwd='production-src',stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
def collect():
 with (out/'client.log').open('w',encoding='utf8') as f:
  for line in p.stdout:
   f.write(line);f.flush();lines.append(line)
t=threading.Thread(target=collect,daemon=True);t.start()
try:
 deadline=time.monotonic()+600
 while time.monotonic()<deadline:
  text=''.join(lines)
  if any(x in text for x in ['Mixin apply for mod oceancanvas failed','Critical injection failure','Exception in thread "Render thread"','NoSuchMethodError','NoClassDefFoundError']):raise RuntimeError('Client compatibility failure; preserve client.log')
  if 'RUNTIME-BUILD-IDENTITY build=v253.125.83 metadata=26.3-v253.125.83' in text and 'OpenAL initialized' in text and re.search(r'Created:.*atlas',text):
   time.sleep(15)
   if p.poll() is not None:raise RuntimeError('Client exited after startup markers')
   (out/'startup.json').write_text('{"passed":true,"scope":"headless title-screen startup; no gameplay or visual certification"}')
   print('CLIENT_263_STARTUP_PASS');break
  if p.poll() is not None:raise RuntimeError('Client exited before startup markers')
  time.sleep(1)
 else:raise TimeoutError('Client startup not observed within ten minutes')
finally:
 if p.poll() is None:
  os.killpg(p.pid,signal.SIGTERM)
  try:p.wait(timeout=30)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
 t.join(timeout=5)
