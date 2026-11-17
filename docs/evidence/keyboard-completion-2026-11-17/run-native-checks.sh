#!/bin/sh
set -eu
for smoke in KeyboardDialogRoutesSmoke CommandRolloverSmoke KeyboardProfileSmoke KeyboardPanelSmoke KeyboardWorkflowSmoke InlineTemplateSmoke KeyboardNestedDialogSmoke KeyboardContextMatrixSmoke; do
  java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar "dev.turboj.app.$smoke" > "/private/tmp/turboj-final-$smoke.log" 2>&1
  printf 'PASS %s\n' "$smoke"
done
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.AILivePrecedenceSmoke http://127.0.0.1:11434 qwen2.5-coder:1.5b-base > /private/tmp/turboj-final-AILivePrecedenceSmoke.log 2>&1
printf 'PASS AILivePrecedenceSmoke\n'
