# CLAUDE.md — F1 Clash Coach

App Android pessoal que lê a tela do jogo **F1 Clash** (Hutch, temporada 2026, jogo em PT-BR), monta a conta do
jogador (garagem, séries, eventos, clube, corridas) e dá conselhos automáticos: painel flutuante sobre o jogo + telas
do app + IA. O usuário (Leandro, presidente do clube "FLA F1") só usa Android: o APK é gerado no GitHub Actions.
Responda sempre em **português**, curto e direto.

> Este repositório NÃO é o "OSM AI Coach Pro". Não existem aqui: leitura de sessões OSM, quatro slots, geração de
> táticas nem calendário de partidas. Partes da IA e da captura foram portadas do OSM Coach (mesmo autor).

## Regras permanentes (todas as sessões)
- Não refazer análises já documentadas aqui; leia só os arquivos necessários para a tarefa.
- Achar a causa do erro antes de corrigir; corrigir a causa raiz, nunca esconder sintoma/mensagem.
- Não quebrar o que funciona; preservar dados do usuário (`coach-data.json`), chaves e integrações.
- Não criar versões paralelas nem branches; não trocar de branch, fazer commit, push, merge ou PR sem autorização.
- Não adicionar dependências sem justificativa; não expor chaves/tokens/senhas (nem copiar a senha do keystore).
- Nada destrutivo sem autorização. Sem soluções simuladas: integrar de verdade.
- Rodar os testes relevantes e conferir o resultado; se não deu para validar (ex.: Android sem SDK), dizer claramente.
- Respostas curtas; sem logs longos nem reprodução de arquivos inteiros.
- Dados de jogo nunca inventados: número só com fonte (tela lida, catálogo, histórico). Sem dado → dizer "sem dados".

## Arquitetura
Gradle multi-módulo (`settings.gradle.kts`): `:core` (Kotlin JVM puro, testável) e `:app` (Android, Compose).
Versões: AGP 8.12.0, Kotlin 2.2.20, Java 17, compileSdk/targetSdk 36, minSdk 26, Compose BOM, ML Kit OCR,
kotlinx-serialization, coroutines, jsoup. `applicationId` = `com.leandro.f1clashcoach`.

Fluxo: Acessibilidade tira print (Android 11+) → `FrameProcessor` → OCR ML Kit (`OcrEngine`) →
`ScreenReader.parse` / `GameReader` classificam a tela e extraem dados → `GameSync` + `Catalog.enrich` atualizam a
garagem → `CoachStore` salva → overlay + telas do app + notificações + IA.

### `core/src/main/kotlin/com/leandro/f1clashcoach/core/`
- `engine/GameReader.kt` — classificador de telas (`classify`) e leitores de cada tela (pilotos, carros, reforços,
  clube, GP, objetivos, séries, pneus, HUD de corrida, resultado). Coração da leitura; `Txt` = utilidades de OCR
  (fuzzy `has`, trata troca "a"↔"z" da fonte do jogo).
- `engine/ScreenReader.kt` — `GameScreen` e `parse(lines, lastSlot)`.
- `engine/GameSync.kt` — aplica uma leitura à `Garage` (casa pilotos por nome+raridade; especiais por edição).
- `model/Catalog.kt` + `src/main/resources/catalog2026.json` — 46 peças e 88 pilotos (22 lendários) com atributos e
  custo por nível (dados F1Clash_Setup, MIT, licença em `catalog2026.LICENSE`). Também `RarityColors` (cor da carta →
  raridade) e `sameName` (nomes tolerantes ao OCR).
- `engine/Boosters.kt` — 15 reforços 2026 catalogados à mão a partir das telas (ícone→atributo) + plano por trecho.
- `engine/LiveWear.kt` — desgaste medido na corrida → volta do pit; `RangePlanner` (plano pelas faixas do jogo).
- `engine/Insights.kt` — `DuelOddsCalc` (% só com ≥8 corridas parecidas), `TrackPlaybook`, `GpPlanner`, `StoreWatch`.
- `engine/ClubManager.kt` — membros, histórico de reputação, regra, quem excluir.
- Outros: `UpgradePlanner`, `Progression` (rumo à S12), `DailyBriefing`, `LiveCoach`, `RaceStrategist`/`WearModel`
  (modelo antigo, uso residual), `SetupOptimizer`, `HistoryAnalyzer`, `RaceHud`.
- `ai/` — `CoachPrompts` (prompt de sistema + `accountContext`), `ClaudeAdvisor` (SDK anthropic-java), `GeminiAdvisor`.
- `model/` — `GameModels` (Part, Driver, Garage, RaceRecord…), `Series` (S7–S12), `Tracks`, `GameModes`.

### `app/src/main/java/com/leandro/f1clashcoach/`
- `capture/CoachAccessibilityService.kt` — captura principal (takeScreenshot, só com F1 Clash em primeiro plano;
  painel escondido durante o print). `CoachCaptureService.kt` = MediaProjection, só fallback < Android 11.
- `capture/FrameProcessor.kt` — OCR → handle por tela; raridade por cor; recorte de fotos (`art/ArtStore`);
  mensagens do overlay; análise da IA pós-derrota; plano de pneus/reforços.
- `capture/OverlayController.kt` — bolinha arrastável + faixa não tocável na corrida (não pode atrapalhar o jogo).
- `ai/AiClient.kt` (porte do OSM: Gemini multi-chave, Groq/OpenRouter/Mistral formato OpenAI, Claude; ordem
  aprendida, cotas, `test()`, `askGrounded`), `AiHub.kt` (adapta para `AiAdvisor`), `AiKeys.kt`, `Diag.kt` (diagnóstico + crash).
- `data/CoachStore.kt` — `CoachData` em JSON (`filesDir/coach-data.json`); `Settings.kt`.
- `notify/Notifier.kt` — notificações + `Reminders` (AlarmManager).
- `ui/` — `MainActivity` (abas Início/Garagem/Evoluir/Corrida/Clube, IA em botão flutuante, Ajustes, Notícias),
  telas `*Screen.kt`, componentes em `Graphics.kt`/`GarageVisual.kt`, tema em `Theme.kt`.

Docs antigos: `README.md`, `ANDROID_ONLY.md` (instalar pelo celular), `SOURCES.md`. `ARCHITECTURE.md` está
**desatualizado** (fala de MediaProjection como principal e Claude como padrão).

## Comandos
- Testes do núcleo (rápido, sem Android): `./gradlew :core:test` → resultados em `core/build/test-results/test/*.xml`.
- APK release: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
- Ambiente cloud desta conta: `dl.google.com` bloqueado → **o `:app` não compila localmente**; só o CI valida o app.
  O `:core` compila e testa localmente (Gradle 8.14.3 em `/opt`; se o wrapper falhar, use um projeto auxiliar que
  inclua `:core` com `gradle :core:test`).

## CI / APK (`.github/workflows/build-apk.yml`)
Push em qualquer branch, PR ou manual: `:core:test` → `:app:assembleRelease` → artefato `F1-Clash-AI-Coach-APK`
(`F1-Clash-Coach.apk`). Na `main` publica Release `v1.0.<run>`. `versionCode` = `GITHUB_RUN_NUMBER`.
Assinatura: `keystore/coach.jks`, configurada em `app/build.gradle.kts` (não mudar o keystore: quebra a atualização
por cima). Usa o SDK do runner (não usar `android-actions/setup-android`, falhou antes). Sem Vercel.
Instalação: Play Protect bloqueia (app usa Acessibilidade) → usuário desliga a verificação, instala e religa;
Android 13+ pode exigir "Permitir configurações restritas".

## Testes e fixtures
- `core/src/test/resources/frames.json`: linhas de OCR (RapidOCR) de quadros reais dos vídeos do usuário,
  formato `[texto, left, top, right, bottom]` normalizado 0–1. Chaves = nome da tela (ex.: `team_setup`,
  `drivers_special`, `drivers_legends`, `club_members`, `boosters`, `race_*`).
- Toda mudança de leitura deve ter teste com fixture real (`RealFramesTest`, `DriverTabsTest`, `ClubTest`,
  `CatalogTest`, `RaceTruthTest`, `EventFramesTest`, `RaceFramesTest`, `VideoSyncTest`, `EngineTest`).
- O OCR de teste (RapidOCR) é pior que o ML Kit do celular: se funciona no teste, tende a funcionar no aparelho.

## Fatos do jogo já confirmados (não reanalisar)
- Visual do jogo: fundo claro lilás, painéis azul-marinho, botões amarelos, "CORRIDA" vermelho, fonte grossa itálica.
- Cartas de piloto: azul=Comum, laranja=Rara, roxo=Épica (mesmo piloto existe nas 3), dourado=Lendário (aba
  própria), Edições Especiais com cor da equipe (aba própria; coleções "Escolhas do Paddock" e "…: Turbo").
  Grade 4 por linha; caixa "nível | cartas/necessárias"; nível = posição de "necessárias" em [4,10,20,50,100,200,400,1000,2000,4000].
- Totais na conta do usuário em out/2026: pilotos 44/66 normais, 7/23 lendários, 2/16 especiais; equipe 1071;
  série 8; escalação D. Hill (Lendário nv1) + L. Lawson (Épico nv1).
- Reforços: 1 por piloto na largada + 1 a cada pit; "Largada" só vale na 1ª volta; não aumentam Classificação.
  45 reforços catalogados em `Boosters.all` (nomes e bônus conferidos nos vídeos).
- Corrida (Duelos e GP mesma tela): HUD "Volta x/N"; tela de pneus mostra voltas totais, faixa por composto
  (ex. Soft 1-2, Medium 2-3, Hard 3-3/3-5) e tempo por volta. Voltas só da corrida/tela de pneus (cartões de série
  no menu mostram "N voltas" e confundiam).
- GP: níveis Júnior/Competidor/Contendor/Campeão com limite de séries, lendários às vezes proibidos, especiais permitidos.
- Regra do clube FLA F1 (lida do jogo): "Zero estrelas na Exibição atual e < 100 na anterior → exclusão;
  2k em duas seguidas → promoção; < 500 → rebaixado".
- Sem API pública do jogo; atributos das Edições Especiais não existem na web.

## Estado das funcionalidades
Funcionando (validado por `:core:test` com quadros reais): build/APK; IA multi-provedor; captura por
Acessibilidade; overlay; notificações; leitura de peças, pilotos (nomes canônicos do catálogo + `Catalog.dedupe`,
migração dos dados salvos em `CoachStore.migrate`), lendários e especiais; reforços com bônus; totais do carro
(número colado no rótulo, pit "4.34s"); clube com a regra aplicada (excluir/rebaixar/promover, `ClubManager`);
cartões automáticos de conselho (`core/engine/Advisor.kt`) na Início; Garagem no estilo do jogo (4 por linha,
abas Pilotos/Lendários/Especiais/Carros/Reforços, fotos recortadas em `ArtStore`, pasta `art2`); chat de IA
só opcional (ícone no topo).
Não validado no aparelho: conselhos durante a corrida (pit/reforços) e o visual novo (só compilado no CI).

## Convenções
- Lógica e leitura no `:core` (puro, testável); `:app` só captura, persistência, UI e Android.
- Novos campos em classes `@Serializable` sempre com valor padrão (compatibilidade do `coach-data.json`).
- Textos de UI em PT-BR simples para usuário leigo; números com fonte ou "sem dados".
- Compose: cores em `Theme.kt`; componentes reutilizáveis em `Graphics.kt`/`GarageVisual.kt`.
- Não usar `FLAG_SECURE` no overlay da acessibilidade (o print falha); esconder com alpha.

## Segurança e erros
- Chaves de IA só em SharedPreferences `ai_settings` (nunca em código, logs ou commits).
- Falhas de captura/OCR/IA vão para `Diag` (mostrado em Início › Diagnóstico); crash salvo por `Health`.
- IA: `AiClient.ask` tenta provedores em ordem e nunca trava; erro claro quando nenhuma chave.

## Diagnosticar
1. Pedir/usar o texto de Início › Diagnóstico (telas reconhecidas, último OCR, último erro, crash).
2. Reproduzir com fixture em `frames.json` (OCR do quadro real) e escrever o teste antes de corrigir.
3. Erro de compilação do app só aparece no CI: ler o log do job, corrigir, conferir de novo.

## Tarefa concluída quando
- `:core:test` passa (todos) e o CI do APK fica verde no commit.
- Mudança de leitura tem teste com dado real; números conferidos contra o vídeo do jogo.
- Nada que funcionava regrediu; dados salvos continuam abrindo.
- Usuário informado do que foi e do que não foi validado (e como instalar).
