# LM Chat for PhpStorm

A JetBrains IDE plugin that brings your own models into PhpStorm: chat, project-aware tools and
inline code completion, backed by any OpenAI-compatible server. Built for **PhpStorm 2022.1.x**
(platform build 221) and tested against local servers such as **LM Studio** and **Ollama**, as
well as hosted free tiers such as **OpenRouter** and **OpenCode Zen**.

Nothing is sent anywhere you haven't configured. With a local provider your code never leaves
your network.

## Features

- **Multiple providers.** Add LM Studio, Ollama, OpenRouter, OpenCode Zen, or any
  OpenAI-compatible endpoint. Models from all providers appear in one dropdown as
  `Provider / model`. A per-provider model filter keeps long lists manageable
  (for example `:free` for OpenRouter).
- **Streaming chat** with a styled transcript: separate colors for your requests and the
  answers, full-width code blocks with syntax highlighting in your editor color scheme,
  `inline code`, bold text and a spinner-ring Stop button.
- **Project tools** (optional): the model can read the active file, list directories, read files,
  search text, and propose edits. Reads are limited to the project directory, and every write
  (`write_file`, `replace_in_file`) needs your approval in a dialog.
- **Inline code completion** with gray ghost text; press `Tab` to accept. Three modes:

  | Mode | Endpoint | Use it for |
  |------|----------|------------|
  | Chat | `/v1/chat/completions` | Any model, no setup |
  | FIM | `/v1/completions` | Code models; choose a template (Qwen-Coder, StarCoder/Granite, DeepSeek-Coder) |
  | Ollama FIM | `/api/generate` with `suffix` | Ollama models whose template supports infill; the server applies the template |

  The completion model is chosen separately from the chat model. You can configure the number
  of context lines before and after the cursor, the maximum suggested lines, the typing delay
  and the token limit.
- **Secrets in Password Safe.** API keys are stored in the IDE's credential store, not in the
  settings XML.

## Requirements

- PhpStorm 2022.1.x (also loads in other IntelliJ-platform IDEs of build 221)
- To build: JDK 11 or 17 and Gradle 7.6 (the IntelliJ Gradle plugin used here does not run on
  newer Gradle versions)
- An OpenAI-compatible server reachable from your machine

## Build and install

```bash
gradle wrapper --gradle-version 7.6.4   # once, to generate ./gradlew
./gradlew buildPlugin                   # produces build/distributions/lm-chat-<version>.zip
```

In PhpStorm: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and choose the zip.
`./gradlew runIde` starts a sandbox IDE (it downloads IntelliJ Community 2022.1.3).

## Configuration

Open **Settings → Tools → LM Chat**.

1. **Providers:** press **+** and pick a preset (LM Studio, Ollama, OpenRouter, OpenCode Zen) or
   *Custom*. The base URL should end in `/v1` (or the provider's equivalent); a bare
   `http://host:port` gets `/v1` added. Paste the API key if the provider needs one.
2. Press the reload icon in the chat tool window to fetch the models.
3. **Chat:** temperature, system prompt and the *Enable project tools* switch.
4. **Inline completion:** pick the provider and model (the refresh icon next to the model field
   loads the list), choose a mode, and adjust the context options.

### LM Studio

Enable the local server and *Serve on Local Network* if the IDE runs on another machine. The
first request to a model can be slow if LM Studio loads it on demand.

### Ollama

Run `ollama serve` (set `OLLAMA_HOST=0.0.0.0:11434` to accept connections from other machines).
For *Ollama FIM*, use a model whose template contains `{{ .Suffix }}` (check with
`ollama show <model>`). Ollama's default context window is small; if long files give odd
suggestions, lower the "context lines before cursor" setting or raise `num_ctx` in a Modelfile.

### Free cloud tiers

Free models and their limits change often. Each tool round and each completion is a separate
request, so rate-limited tiers run out quickly. Prefer a local model for inline completion.

## Using it

- **Chat:** type in the box and press `Enter` (`Shift+Enter` for a new line). While a response
  is running, the Send button is replaced by Stop.
- **Completion:** type, pause, and press `Tab` to accept the gray suggestion. Suggestions
  appear only at the end of a line, and the IDE's own completion popup takes priority.

## Privacy and safety

- Tool results (file contents) are sent to the selected provider. Untick *Enable project tools*
  or use a local provider if that is a concern.
- Free tiers of hosted providers may log or train on prompts; check their terms.
- File changes proposed by the model are never written without your confirmation.

## Project layout

```
src/main/kotlin/dev/lmchat/
  Settings.kt                persisted settings, provider table, settings page
  api/OpenAiClient.kt        streaming chat, tool-call parsing, completions, Ollama FIM
  tools/ProjectTools.kt      sandboxed project tools
  completion/Completion.kt   ghost text, Tab handler, completion requests
  ui/ChatPanel.kt            tool window, toolbar, Send/Stop buttons
  ui/ChatView.kt             styled transcript and code highlighting
src/main/resources/
  META-INF/plugin.xml
  icons/send.svg, stop.svg
```

## Limitations

- Targets build 221 only (`sinceBuild 221`, `untilBuild 221.*`); newer IDEs expose a different
  inline-completion API and would need changes.
- Completion context is the current file only.
- The code highlighter is a lightweight tokenizer, not a full language parser.
- Tool support depends on the model; models without tool calling just answer in text.

## License

No license chosen yet. Add a `LICENSE` file before publishing.