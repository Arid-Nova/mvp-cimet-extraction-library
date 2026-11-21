"""Check reviewed Java sources with an immutable, checksum-verified formatter release."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.24.0"
SHA256 = "812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1"
URL = (
    "https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/"
    f"{VERSION}/google-java-format-{VERSION}-all-deps.jar"
)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Format the listed files in place")
    parser.add_argument("--jar", type=Path, help="Use an already downloaded, matching formatter JAR")
    args = parser.parse_args()
    jar = args.jar or ROOT / "target" / "tools" / f"google-java-format-{VERSION}.jar"
    
    if not jar.is_file():
        if args.jar:
            raise SystemExit("Formatter JAR does not exist")
        jar.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = response.read(8 * 1024 * 1024 + 1)
        if len(data) > 8 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != SHA256:
            raise SystemExit("Formatter download does not match the pinned checksum")
        jar.write_bytes(data)
    
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
        raise SystemExit("Formatter JAR does not match the pinned checksum")
    
    files = [
        line.strip()
        for line in (ROOT / ".java-format-files").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]

    if not files or any(not (ROOT / file).is_file() for file in files):
        raise SystemExit("The Java format manifest contains missing files or is empty")
    
    mode = ["--replace"] if args.apply else ["--dry-run", "--set-exit-if-changed"]
    subprocess.run(["java", "-jar", str(jar.resolve()), *mode, *files], cwd=ROOT, check=True)
    print(f"Java formatting {'applied to' if args.apply else 'checked for'} {len(files)} files")


if __name__ == "__main__":
    main()
