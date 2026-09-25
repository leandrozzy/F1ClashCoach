# Arquitetura técnica

## Módulos principais

- `service/ScreenCaptureService.kt`: foreground service MediaProjection; continua ativo ao abrir o F1 Clash.
- `ocr/OcrFrameAnalyzer.kt`: OCR local, throttling e normalização para landscape.
- `engine/ScreenClassifier.kt`: classifica a tela atual.
- `engine/OcrParser.kt`: extrai Série, pista, volta, clima, posições e percentuais detectáveis.
- `engine/CoachEngine.kt`: motor determinístico de decisão de baixa latência.
- `service/OverlayController.kt`: overlay seguro e arrastável.
- `data/CoachStore.kt`: garagem, histórico e notícias no aparelho.
- `network/NewsRepository.kt`: Hutch + AllClash.
- `ai/GeminiAdvisor.kt`: análise de conta sob demanda usando somente texto.

## Princípios

1. Corrida não depende de API remota.
2. Nenhum valor desconhecido é preenchido por inferência silenciosa.
3. IA generativa não decide em loop durante a corrida; o motor local é previsível e rápido.
4. Web e IA servem para meta/evolução/eventos, onde latência não é crítica.
5. O app aconselha; não executa ações dentro do jogo.
