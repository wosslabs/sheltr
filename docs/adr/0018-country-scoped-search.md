# ADR-0018: Search is limited to the selected country

## Status
Accepted 2026-11-03.

## Context
Country used to raise a matching document's rank and never removed a passage. With the country unset, a question such as wildfire cited every source in the bundle, including guidance written for a different country.

## Decision
1. On launch the app sets the country from the device locale. It does not read GPS. The privacy note stands: there is no location request.
2. A debug build can change that country in Settings. A release build keeps the detected country.
3. Retrieval returns passages whose document country matches. When none of those pass the relevance check, it uses documents with no country (global guidance). Documents from any other country are left out. Keyword search and vector search follow the same rule.

## Consequences
- A country with its own approved page no longer also shows the global guide for that question.
- A country with no approved page still receives global guidance, such as the IFRC key messages.
- The displayed passage joins words that the PDF extract split across lines. The stored chunk, the citation, and the text given to the model stay as published.
