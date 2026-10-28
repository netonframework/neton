"""Source markers complement ABI dumps, which do not encode all opt-in contracts."""
from pathlib import Path
import re

DECLARATION = re.compile(r"^(?:(?:public|data|sealed|enum|abstract|open|value|inline|suspend|infix|operator|expect|actual|const) )*(?:class|interface|object|fun|val|var|typealias)\b", re.MULTILINE)

def require_markers(file, marker):
    text = file.read_text()
    for match in DECLARATION.finditer(text):
        # The marker is attached directly above the public top-level declaration.
        preceding = text[:match.start()].rstrip().splitlines()
        if not preceding or preceding[-1] != "@" + marker:
            raise ValueError(f"Unclassified public declaration: {file}:{text[:match.start()].count(chr(10)) + 1}")

def main():
    root = Path(__file__).resolve().parents[2]
    for file in (root / "neton-websocket/src/commonMain/kotlin/neton/websocket/spi").glob("*.kt"):
        require_markers(file, "ExperimentalWebSocketEngineApi")
    for name in ("SqlxDatabase", "SqlxQueryBuilder", "SqlxTableAdapter"):
        require_markers(root / f"neton-database/src/commonMain/kotlin/neton/database/adapter/sqlx/{name}.kt",
                        "neton.core.annotations.InternalNetonApi")
    print("Internal and experimental API source boundaries verified")

if __name__ == "__main__":
    main()
