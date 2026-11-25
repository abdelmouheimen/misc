#!/usr/bin/env bash
# Décrit l'environnement de mesure (JSON sur la sortie standard).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KIT="$(cd "$HERE/../.." && pwd)"
export HERE KIT
export JAVA_BIN="${JAVA_HOME:-/c/Program Files/Java/jdk-25.0.2}/bin/java"
export IMAGE="${BACKEND_IMAGE:-dpop-lab-backend:bench-$(git -C "$KIT" rev-parse --short HEAD)}"
export BASH_EXE; BASH_EXE="$(cygpath -w "$BASH" 2>/dev/null || echo bash)"
# EnvInfo est compilé une fois : l'image du backend ne contient qu'un JRE (pas de javac).
CLS="$(mktemp -d)"; trap 'rm -rf "$CLS"' EXIT
"$(dirname "$JAVA_BIN")/javac" -d "$CLS" "$HERE/EnvInfo.java"
export CLS_WIN; CLS_WIN="$(cygpath -w "$CLS" 2>/dev/null || echo "$CLS")"
export CLS
export HERE_WIN; HERE_WIN="$(cygpath -w "$HERE" 2>/dev/null || echo "$HERE")"

python3 - <<'PY'
import json, os, subprocess, datetime, sys
sys.stdout.reconfigure(encoding="utf-8")
E = os.environ
def sh(c):
    r = subprocess.run([E['BASH_EXE'], '-c', c], capture_output=True, timeout=180)
    out = r.stdout if r.stdout.strip() else r.stderr
    return out.decode('utf-8', 'replace').replace('\r', '').strip()
def ps(c):
    return sh(f"powershell.exe -NoProfile -Command \"{c}\"")
def js(c):
    try: return json.loads(sh(c))
    except Exception: return sh(c)
kit, here, java, image = E['KIT'], E['HERE'], E['JAVA_BIN'], E['IMAGE']
pom = lambda f, a: sh(f"grep -A2 '{a}' '{f}' | grep -o '<version>[^<]*' | head -1 | cut -d'>' -f2")
print(json.dumps({
  "date": datetime.datetime.now(datetime.timezone.utc).isoformat(),
  "git": {"commit": sh(f"git -C '{kit}' rev-parse HEAD"),
          "branch": sh(f"git -C '{kit}' rev-parse --abbrev-ref HEAD"),
          "pocSourcesModified": sh(f"git -C '{kit}' status --porcelain -- backend frontend attacks tools docker-compose.yml") != ""},
  "os": ps("(Get-CimInstance Win32_OperatingSystem).Caption + ' ' + (Get-CimInstance Win32_OperatingSystem).Version"),
  "cpu": {"name": ps("(Get-CimInstance Win32_Processor).Name"),
          "cores": ps("(Get-CimInstance Win32_Processor).NumberOfCores"),
          "logicalProcessors": ps("(Get-CimInstance Win32_Processor).NumberOfLogicalProcessors")},
  "cpuTopology": js(f"powershell.exe -NoProfile -NoLogo -ExecutionPolicy Bypass -File '{E['HERE_WIN']}\\cpu_topology.ps1'"),
  "memoryGiB": round(int(ps("(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory") or 0) / 2**30, 1),
  "power": {"batteryStatus": ps("(Get-CimInstance Win32_Battery).BatteryStatus"),
            "note": "Win32_Battery.BatteryStatus : 1 = sur batterie, 2 = sur secteur"},
  "hostJava": sh(f"'{java}' -version").splitlines(),
  "hostJcaProviders": js(f"'{java}' -cp '{E['CLS']}' EnvInfo"),
  "containerJava": sh(f"docker run --rm --entrypoint java {image} -version").splitlines(),
  "containerJcaProviders": js(f"MSYS_NO_PATHCONV=1 docker run --rm -v '{E['CLS_WIN']}:/s:ro' --entrypoint java {image} -cp /s EnvInfo"),
  "springBoot": pom(f"{kit}/backend/pom.xml", 'spring-boot-starter-parent'),
  "nimbusJoseJwt": pom(f"{kit}/backend/pom.xml", 'nimbus-jose-jwt'),
  "jmh": sh(f"grep -o '<jmh.version>[^<]*' '{kit}/benchmarks/jvm/pom.xml' | cut -d'>' -f2"),
  "node": sh("node --version"),
  "playwright": sh(f"cd '{kit}/benchmarks/browser' && node -p \"require('./node_modules/playwright/package.json').version\""),
  "docker": {"server": sh("docker version --format '{{.Server.Version}}'"),
             "engineCpus": sh("docker info --format '{{.NCPU}}'"),
             "engineMemBytes": sh("docker info --format '{{.MemTotal}}'"),
             "kernel": sh("docker info --format '{{.KernelVersion}}'"),
             "os": sh("docker info --format '{{.OperatingSystem}}'")},
  "otherRunningContainers": sh("docker ps --format '{{.Names}} ({{.Image}})' | grep -v dpop-bench").splitlines(),
  "backendImage": {"tag": image, "id": sh(f"docker image inspect {image} --format '{{{{.Id}}}}'")},
  "k6": sh(f"ls '{kit}/benchmarks/load/.tools' 2>/dev/null | grep -E '^k6-v.*-linux-amd64$' | head -1"),
}, ensure_ascii=False, indent=1))
PY
