# AgentForge Service

## Start backend

```bash
# 1. Prepare the local config from the template
cp agentforge-service/agentforge-service-web/src/main/resources/application.yml.example \
   agentforge-service/agentforge-service-web/src/main/resources/application.yml

# 2. Build and run
export AGENTFORGE_MODEL_API_KEY=your-api-key
mvn package -pl agentforge-service/agentforge-service-web -am -DskipTests
java -jar agentforge-service/agentforge-service-web/target/agentforge-service-web-1.0.0-SNAPSHOT.jar
```

The default OpenAI-compatible endpoint is `https://tokenrhythm.studio/v1` and the default model is `deepseek-flash`.

`application.yml` is generated from `application.yml.example` and is git-ignored; edit it locally or override any item with environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `AGENTFORGE_MODEL_BASE_URL` | `https://tokenrhythm.studio/v1` | OpenAI-compatible endpoint |
| `AGENTFORGE_MODEL_API_KEY` | *(empty)* | API key, required at call time |
| `AGENTFORGE_MODEL_NAME` | `deepseek-flash` | Model name |
| `AGENTFORGE_MODEL_TEMPERATURE` | `0.3` | Sampling temperature |
| `OPENREACH_BASE_URL` | `http://openreach.changlu.cloud` | OpenReach service base URL; leave empty to disable the web tools |

### Web tools (OpenReach, local mode)

When `OPENREACH_BASE_URL` (or `agentforge.openreach.base-url`) is configured, the agent gains four
local `@Tool` methods scanned by `LocalToolFactory`:

| Tool | Description |
| --- | --- |
| `webSearch` | Public web search, returns candidate results |
| `webImageSearch` | Image search, returns validated `imageUrl`s |
| `webRead` | Read the main text of a public web page |
| `webCurl` | Safe read-only GET/HEAD for GitHub API / raw source / public JSON |

Live tests hit the real service by default; skip with `-Dagentforge.openreach.live=false`, or point
elsewhere with `-Dagentforge.openreach.base-url=<url>`.

## Start frontend

```bash
cd agentforge-service/agentforge-service-ui
npm install
npm run dev
```

The UI sends `POST /api/chat/stream` and consumes the unified SSE events: `session`, `step`, `think`, `tool_call`, `resp`, `resp_end`, and `error`.
