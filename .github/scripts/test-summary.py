"""JUnit XML 결과를 GitHub 작업 요약(Markdown)으로 만든다.
건너뛴(SKIPPED) 테스트는 "검증 안 됨"으로 따로 보여준다 — 통과로 세지 않는다."""
import glob
import sys
import xml.etree.ElementTree as ET

directory, title = sys.argv[1], sys.argv[2]
total = failed = errors = skipped = 0
skipped_cases = []
for path in sorted(glob.glob(f"{directory}/*.xml")):
    suite = ET.parse(path).getroot()
    total += int(suite.get("tests", 0))
    failed += int(suite.get("failures", 0))
    errors += int(suite.get("errors", 0))
    skipped += int(suite.get("skipped", 0))
    for case in suite.iter("testcase"):
        node = case.find("skipped")
        if node is not None:
            reason = (node.get("message") or "").strip()
            skipped_cases.append(f"{case.get('classname', '').split('.')[-1]} › {case.get('name')} — {reason}")

passed = total - failed - errors - skipped
print(f"### {title}")
if total == 0:
    print("실행된 테스트가 없습니다 (결과 파일 없음 또는 빌드 실패).")
else:
    print(f"| 전체 | 통과 | 실패 | 오류 | 건너뜀(검증 안 됨) |\n|---|---|---|---|---|\n| {total} | {passed} | {failed} | {errors} | {skipped} |")
    if skipped_cases:
        print("\n<details><summary>건너뛴 테스트 (검사 대상이 아직 없어 검증되지 않은 규칙)</summary>\n")
        for line in skipped_cases:
            print(f"- {line}")
        print("\n</details>")
print()
