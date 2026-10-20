#!/usr/bin/env bash
# Hook de Stop: si la librería (src/main o pom.xml) cambió después de la última sincronización de la documentación,
# impide terminar y pide lanzar el subagente docs-sync. El subagente actualiza .claude/.docs-sync-stamp al acabar.
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

input=$(cat)
# Evita bucles: si ya se bloqueó una vez en este turno, deja terminar
if printf '%s' "$input" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
	exit 0
fi

stamp=.claude/.docs-sync-stamp
if [ -f "$stamp" ]; then
	changed=$(find src/main pom.xml -type f -newer "$stamp" 2>/dev/null | head -n 20)
else
	changed="(sin marca de sincronización previa)"
fi
[ -z "$changed" ] && exit 0

reason="La librería ha cambiado desde la última actualización de la documentación. Lanza el subagente docs-sync (Agent, subagent_type \"docs-sync\") para actualizar docs/es, docs/en, README.md y README.en.md. Ficheros: $(printf '%s' "$changed" | tr '\n' ' ')"
reason=${reason//\\/\\\\}
reason=${reason//\"/\\\"}
printf '{"decision":"block","reason":"%s"}\n' "$reason"
