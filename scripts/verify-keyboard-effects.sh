#!/usr/bin/env bash
# Visible native checks must run sequentially: concurrent JVM windows contend for macOS focus.
# Build first with ./mvnw install. Fixtures/preferences live in temporary directories.
set -u
cd "$(dirname "$0")/.."
suite_output="${KEYBOARD_EFFECTS_OUTPUT:-app/target/keyboard-effects-suite}"
artifact="${KEYBOARD_EFFECTS_ARTIFACT:-app/target/turboj-app.jar}"
runtime="${KEYBOARD_EFFECTS_JAVA:-java}"
classes="${KEYBOARD_EFFECTS_CLASSES:-app/target/test-classes}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --artifact|--java|--output|--classes|--cherry-jar)
      if [[ $# -lt 2 ]]; then echo "Missing value for $1" >&2; exit 2; fi
      case "$1" in
        --artifact) artifact="$2" ;; --java) runtime="$2" ;;
        --output) suite_output="$2" ;; --classes) classes="$2" ;;
        --cherry-jar) CHERRY_TEST_JAR="$2" ;;
      esac
      shift 2 ;;
    *) break ;;
  esac
done
mkdir -p "$suite_output"
mkdir -p "$classes"
if [[ ! -f "$artifact" ]]; then
  echo "Build the app first: ./mvnw install" >&2
  exit 2
fi
checks=(KeyboardActionEffectsSmoke KeyboardRefactoringEffectsSmoke KeyboardMultiFileEffectsSmoke
  KeyboardBranchEffectsSmoke KeyboardExplorerEffectsSmoke KeyboardPomEffectsSmoke
  KeyboardNavigationEffectsSmoke KeyboardExecutionEffectsSmoke KeyboardTestEffectsSmoke KeyboardViewEffectsSmoke
  KeyboardIntelligenceEffectsSmoke KeyboardApplicationEffectsSmoke)
regressions=(CommandRolloverSmoke KeyboardEditingSmoke KeyboardProfileSmoke KeyboardPanelSmoke
  KeyboardWorkflowSmoke KeyboardNestedDialogSmoke KeyboardDialogRoutesSmoke
  FirstLaunchKeyboardSmoke UsagesNavigationAcceptance OutputCommandKeyboardSmoke)
# Explicit-only boundary checks: AI refreshes an installed Ollama model and creates/removes
# disposable loopback Keychain credentials. Never include that side effect in the default run.
boundaries=(RecoverySmoke FormatterSmoke CherryNativeSmoke KeyboardMavenBoundarySmoke KeyboardAIBoundarySmoke AIInteractionSmoke LiveTemplateWorkflowSmoke LiveTemplatePrecedenceSmoke LiveTemplateDialogSmoke InlineTemplateSmoke InlineSemanticTemplateSmoke
  QuickFixPopupSmoke SaveAutomationSmoke OutputStatusSmoke RunTargetIsolationSmoke SaveFocusSmoke BufferSaveSmoke)
sources=(app/src/test/java/dev/turboj/app/NativeWindowTestSupport.java)
for check in "${checks[@]}" "${regressions[@]}" "${boundaries[@]}"; do sources+=("app/src/test/java/dev/turboj/app/$check.java"); done
javac -cp "app/target/test-classes:$artifact" -d "$classes" "${sources[@]}" || exit 2
if [[ "${1:-}" == "--regressions" ]]; then
  checks+=("${regressions[@]}")
elif [[ "${1:-}" == "--only" && $# -gt 1 ]]; then
  shift
  allowed=" ${checks[*]} ${regressions[*]} ${boundaries[*]} "
  for check in "$@"; do
    if [[ "$allowed" != *" $check "* ]]; then echo "Unknown check: $check" >&2; exit 2; fi
  done
  checks=("$@")
elif [[ -n "${1:-}" ]]; then
  echo "Usage: $0 [--artifact JAR --java JAVA --output DIR --classes DIR --cherry-jar JAR] [--regressions | --only CheckName ...]" >&2
  exit 2
fi
shasum -a 256 "$artifact" > "$suite_output/artifact.sha256"
"$runtime" -version > "$suite_output/runtime.txt" 2>&1
shasum -a 256 "${sources[@]}" > "$suite_output/test-sources.sha256"
shasum -a 256 app/src/test/resources/save-automation/* > "$suite_output/test-resources.sha256"
# Classpath test entry points do not inherit the production -jar manifest's Add-Exports.
java_options=(--enable-native-access=ALL-UNNAMED)
for package in api code file parser tree util; do
  java_options+=("--add-exports=jdk.compiler/com.sun.tools.javac.$package=ALL-UNNAMED")
done
printf 'check\texit_status\tstarted_utc\tfinished_utc\n' > "$suite_output/results.tsv"
failed=0
# A locked macOS session cannot receive native keyboard events. Do not turn that
# environment blocker into a cascade of alleged application failures.
desktop_ready() {
  [[ "$(uname -s)" != "Darwin" ]] && return 0
  ioreg -a -n Root -d 1 | python3 -c 'import plistlib,sys; d=plistlib.loads(sys.stdin.buffer.read()); users=(d[0] if isinstance(d,list) else d).get("IOConsoleUsers",[]); sys.exit(3 if any(u.get("kCGSSessionOnConsoleKey") and u.get("CGSSessionScreenIsLocked") for u in users) else 0)'
}
for check in "${checks[@]}"; do
  if ! desktop_ready; then echo "BLOCKED: unlock the macOS desktop before native keyboard checks" >&2; exit 3; fi
  started="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "Running $check"
  invocation=("$runtime" "${java_options[@]}" -cp "$classes:app/src/test/resources:$artifact" "dev.turboj.app.$check")
  if [[ "$check" == CherryNativeSmoke ]]; then
    if [[ ! -f "${CHERRY_TEST_JAR:-}" ]]; then echo "Set CHERRY_TEST_JAR to the qualified companion CLI JAR" >&2; exit 2; fi
    invocation+=("$CHERRY_TEST_JAR")
    shasum -a 256 "$CHERRY_TEST_JAR" > "$suite_output/cherry-artifact.sha256"
  fi
  "${invocation[@]}" > "$suite_output/$check.txt" 2>&1
  status=$?
  printf '%s\t%s\t%s\t%s\n' "$check" "$status" "$started" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$suite_output/results.tsv"
  if [[ $status -ne 0 ]]; then
    if ! desktop_ready; then echo "BLOCKED: desktop locked during $check; remaining checks not run" >&2; exit 3; fi
    failed=1
    echo "FAIL $check (see $suite_output/$check.txt)"
  else
    echo "PASS $check"
  fi
done
exit "$failed"
