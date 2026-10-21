# Arquitetura

## Fluxo do Coach ao vivo

1. `CoachCaptureService` (serviço em primeiro plano, tipo `mediaProjection`) espelha a tela num `VirtualDisplay` → `ImageReader`.
2. A cada `ocrIntervalMs` pega o frame mais recente, converte em `Bitmap` e roda o OCR do ML Kit (`OcrEngine`), no aparelho.
3. `ScreenReader.parse` classifica a tela (corrida, classificação, resultado, peças, pilotos, upgrade, loja, evento…) e extrai volta, posições, % de pneu, compostos, clima, pista, atributos, nível e cartas. Funciona com o jogo em inglês ou português.
4. Conforme a tela:
   - **Corrida** → `LiveCoach.advise` gera dicas priorizadas (🔴 urgente, 🟡 importante, ⚪ info) e o overlay mostra. Os stints de pneu são acompanhados e viram `TyreObservation` para calibrar o `WearModel`.
   - **Pista detectada** → `SetupOptimizer` + `RaceStrategist` mostram setup e estratégia.
   - **Resultado** → grava um `RaceRecord` (vitória/derrota, grid, chegada, pneus usados, clima).
   - **Peças/pilotos** → `CoachStore.mergeScanned*` atualiza a garagem.
5. Botão 🤖 do overlay → envia o último frame + contexto da conta para a IA e mostra a resposta no painel.

## Motores (módulo `core`)

- `SetupOptimizer`: atributos são aditivos, então o melhor setup é o melhor item de cada slot segundo os pesos da pista (`TrackType`). Lista trocas em relação ao equipado.
- `RaceStrategist`: simula volta a volta todas as combinações de 0–2 paradas, compostos e ritmo (conservar/neutro/atacar) por stint, com clima por volta; descarta planos que estouram o pneu; ordena por tempo relativo.
- `WearModel`: desgaste por volta = base do composto × estresse da pista × gestão de pneus do piloto × ritmo; média bayesiana com as observações reais.
- `LiveCoach`: regras rápidas de clima × pneu, vida do pneu × voltas restantes, disputa (gap) e energia.
- `UpgradePlanner`: ganho ponderado (pelas pistas mais corridas) ÷ custo em moedas, priorizando o que dá para fazer agora dentro do saldo.
- `HistoryAnalyzer`: taxa de vitória geral/recente, grid × chegada, chuva, pistas fracas → diagnósticos acionáveis.

## IA (`core/ai`)

- `ClaudeAdvisor`: SDK oficial `com.anthropic:anthropic-java`, modelo padrão `claude-opus-5-5`, esforço `medium`, imagem em base64, ferramenta `web_search_20260209`, continuação em `pause_turn`, tratamento de recusa e fallback de servidor.
- `GeminiAdvisor`: REST `generateContent` com `google_search` para busca.
- `CoachPrompts`: prompt de sistema em português + resumo compacto da conta (garagem, histórico, upgrades e setup calculados).
