# Project Architecture Guidelines (Spring Boot + Vue 3)

## Backend (Spring Boot 3 + Java 17)
- Use standard layered architecture: Controller -> Service -> Repository.
- Follow RESTful API naming conventions (e.g., `/api/v1/products`).
- Use Lombok, JPA/Hibernate, and validation annotations (`@Valid`, `@NotNull`).

## Frontend (Vue 3 + Tailwind CSS / Element Plus)
- Use Vue 3 Composition API (`<script setup>`).
- State management via Pinia for global states, local reactive refs for component states.
- **UI Design Rule:**
  - Never design plain, boring white-and-black admin forms. Use soft neutral backgrounds (`bg-slate-50` or `bg-zinc-900` for dark mode), subtle shadow (`shadow-sm` or `shadow-md`), and rounded corners (`rounded-xl`).
  - Tables must have hover effects, clean pagination, and clear status badges (e.g., Active/Inactive with green/gray pills).
- Handle loading states, error boundaries, and empty states cleanly for every API call coming from the Spring Boot backend.

## How this project applies the rules
- Backend packages (`backend/src/main/java/ark`):
  - `controller` – thin REST controllers under `/api/v1/...`, request DTOs validated with `@Valid`.
  - `service` – business logic (generation, video tasks, asset library, file library, jobs, settings).
  - `repository` – Spring Data JPA repositories; `entity` – JPA entities (H2 file DB at `<project>/data`).
  - `dto` – request/response DTOs (Lombok / records). `integration.byteplus` – HTTP clients for BytePlus
    (ArkClient = API key data plane, OpenApiClient = AK/SK signed control plane). `config` – settings, web config.
  - Secrets (API key, AK/SK) live in `%USERPROFILE%\.byteplus-ark\config.properties`, never in the DB or API responses.
- Frontend (`frontend/src`): Pinia stores in `stores/`, shared UI in `components/` (`StatusBadge`, `AsyncState`,
  `PageHeader`…), `composables/useAsync.js` for loading/error/empty handling, Tailwind utilities + Element Plus.
- Batch script runner: `ScriptImportService` (Excel → ScriptProject/ScriptClip/ScriptReference), `ScriptService`,
  `ScriptRunner` (@Scheduled video queue), `ScriptImageRunner` (Seedream reference images, prompts from `ScriptPromptBuilder`),
  conditional status updates via `ScriptClipRepository.moveIf`); UI `views/ScriptView.vue`.
- UI language: Vietnamese.
