# OneDrive chat sync and the chat file format

## Azure setup (once)

1. portal.azure.com → Microsoft Entra ID → App registrations → New registration.
   Name = folder name on OneDrive (`Apps/<name>`). Supported account types: **Personal Microsoft accounts only**.
2. Authentication → add platform **Mobile and desktop applications** → custom redirect URI
   `msal3c225cad-e138-4683-9c03-68001d6609c8://auth`; Settings tab → **Allow public client flows = Yes**.
3. API permissions → Microsoft Graph → Delegated → `Files.ReadWrite.AppFolder` (consent happens at sign-in).
4. The client ID is built in (`OneDriveAuth.CLIENT_ID`); Settings → Chats & OneDrive sync → Sign in (browser, authorization code + PKCE, back via `sync/AuthRedirectActivity`). The code is copied to
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
