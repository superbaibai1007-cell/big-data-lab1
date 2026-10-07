#!/usr/bin/env bash
# Exact exercise paths. Refuse pre-existing paths; use a disposable Linux runner.
set -euo pipefail
[[ $(id -un) == hadoop ]] || { echo 'Run as the hadoop user'; exit 1; }
for p in /tmp/a /tmp/a1 /tmp/test /tmp/hello /usr/bashrc1 /usr/test /usr/test2 /test /test.tar.gz; do
  [[ ! -e $p ]] || { echo "Refusing existing path: $p"; exit 1; }
done
set -x
cd /usr/local
pwd
cd ..
pwd
cd ~
pwd
ls -al /usr
cd /tmp
mkdir a
ls -la /tmp
mkdir -p a1/a2/a3/a4
rmdir a
rmdir -p a1/a2/a3/a4
ls -la /tmp
sudo cp ~/.bashrc /usr/bashrc1
mkdir /tmp/test
sudo cp -r /tmp/test /usr/test
sudo mv /usr/bashrc1 /usr/test/
sudo mv /usr/test /usr/test2
sudo rm /usr/test2/bashrc1
sudo rm -r /usr/test2
cat ~/.bashrc
tac ~/.bashrc
# A real pseudo-terminal sends Space (next page), then q to more.
python3 - <<'PY'
import os, pty, select, time
pid, fd = pty.fork()
if pid == 0:
    os.execlp('more', 'more', '-10', os.path.expanduser('~/.bashrc'))
for key in [b' ', b'q']:
    end = time.time() + 1
    while time.time() < end:
        if select.select([fd], [], [], 0.1)[0]:
            try: print(os.read(fd, 65536).decode(errors='replace'), end='', flush=True)
            except OSError: break
    os.write(fd, key)
os.waitpid(pid, 0)
os.close(fd)
print('\nMORE: sent Space and q in a pseudo-terminal')
PY
head -n 20 ~/.bashrc
head -n -50 ~/.bashrc
tail -n 20 ~/.bashrc
tail -n +51 ~/.bashrc
touch /tmp/hello
stat /tmp/hello
touch -d '5 days ago' /tmp/hello
stat /tmp/hello
sudo chown root /tmp/hello
ls -l /tmp/hello
find ~ -type f -name .bashrc
sudo mkdir /test
sudo tar -czf /test.tar.gz -C / test
sudo tar -xzf /test.tar.gz -C /tmp
tar -tzf /test.tar.gz
grep -n 'examples' ~/.bashrc
echo 'LINUX BASICS PASS'
