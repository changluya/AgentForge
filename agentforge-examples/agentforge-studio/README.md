# AgentForge Studio

## Start backend

```bash
export AGENTFORGE_MODEL_API_KEY=your-api-key
mvn package -pl agentforge-examples/agentforge-studio/agentforge-studio-web -am -DskipTests
java -jar agentforge-examples/agentforge-studio/agentforge-studio-web/target/agentforge-studio-web-1.0.0-SNAPSHOT.jar
```

The default OpenAI-compatible endpoint is `https://tokenrhythm.studio/v1` and the default model is `deepseek-flash`.

## Start frontend

```bash
cd agentforge-examples/agentforge-studio/agentforge-studio-ui
npm install
npm run dev
```

The UI sends `POST /api/chat/stream` and consumes the unified SSE events: `session`, `step`, `think`, `tool_call`, `resp`, `resp_end`, and `error`.
