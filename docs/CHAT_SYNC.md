# OneDrive chat sync and the chat file format

## Azure setup (once)

1. portal.azure.com → Microsoft Entra ID → App registrations → New registration.
   Name = folder name on OneDrive (`Apps/<name>`). Supported account types: **Personal Microsoft accounts only**.
   No redirect URI.
2. Authentication → Advanced settings → **Allow public client flows = Yes** (device code flow).
3. API permissions → Microsoft Graph → Delegated → `Files.ReadWrite.AppFolder` (consent happens at sign-in).
4. The client ID is built in (`OneDriveAuth.CLIENT_ID`); Settings → OneDrive sync → Sign in. The code is copied to
   the clipboard and the Microsoft page opens.

## Layout

    Apps/<registration>/chats/<chat id>.json     one chat each
    Apps/<registration>/settings/servers.json    server models incl. API keys

## Chat file (`bigmoe-chat`, version 1)

    { "format": "bigmoe-chat", "version": 1, "id", "title", "createdAt", "updatedAt", "changedAt",
      "modelPath", "modelNames": [], "messages": [ { "id", "role", "content", "timestamp",
      "reasoning"?, "runInfo"?: {}, "variants"?: …, "tokensPerSecond"?, "prefillMs"?, "generatedTokens"?,
      "cacheHitPct"?, "generationElapsedMs"?, "contextUsed"?, "contextTotal"?, "note"?, "prefillTokens"?,
      "thinkingMs"?, "toolMs"? } ] }

Roles: USER, ASSISTANT, SYSTEM, TOOL (tool result, sent as a user turn), CONTEXT (hidden context of the user turn
before it). `changedAt` (ms) decides conflicts: the newer copy wins. Other front ends (LLamaGUI) must keep fields
they do not know when they write a file.
