# 🏁 F1 Clash Coach — treinador com IA para F1 Clash (Android)

Um "OSM Coach" para o **F1 Clash**: lê sua tela enquanto você joga, avisa o que fazer na corrida,
monta o melhor setup para cada pista, planeja seus upgrades, aprende com seus resultados e
usa IA (com busca na web) para trazer o meta atual.

> O app **só aconselha**. Ele não toca na tela nem joga por você (nada de bot/auto-clicker), para não violar as regras do jogo.

## O que ele faz

| Área | O que você ganha |
|---|---|
| **Coach ao vivo** (painel flutuante) | Lê a tela a cada ~1s com OCR local. Na corrida avisa: 🔴 *BOX agora*, troca para intermediário/chuva, *conservar* ou *pode atacar*, quando usar energia/Overtake Mode. Botão **🤖 IA** analisa o print da tela na hora. |
| **Detecção automática** | Reconhece a pista e mostra o setup + estratégia antes da largada. Registra vitória/derrota na tela de resultado. Ao abrir peças e pilotos no jogo, **preenche sua garagem sozinho**. |
| **Garagem** | Peças (7 slots, incluindo bateria) e pilotos com nível, cartas, custo e atributos. Também lê **prints da galeria** em lote. |
| **Corrida** | Otimizador de setup por pista (pesos por perfil: velocidade, equilibrada, técnica, rua) + simulador de estratégia que testa todas as combinações de pneus, paradas e ritmo, com chuva prevista. |
| **Aprendizado** | O desgaste real dos pneus é medido durante as corridas e recalibra o simulador para cada pista. |
| **Evoluir** | Plano de upgrades por *ganho de desempenho por moeda*, cabendo no seu saldo; quais cartas juntar; diagnóstico do histórico (classificação fraca? ritmo fraco? chuva?); pistas boas e ruins. |
| **Coach IA** | Chat com Claude ou Gemini já sabendo tudo da sua conta. Anexe prints, ligue 🌐 para pesquisar o meta, Featured Assets, eventos e guias atuais. Atalhos: meta da semana, plano de 7 dias, eventos, bucks. |
| **Rotina diária** | Checklist do dia (caixas, anúncios, loja, liga, evento, clube). |
| **Notícias** | Patch notes e blog da Hutch, site oficial e guias da AllClash, com resumo por IA. |

## Instalar no Android (sem PC)

1. Faça o merge deste trabalho na branch `main` (pelo GitHub no navegador do celular).
2. O GitHub Actions compila sozinho e publica uma **Release**. Abra a aba **Releases** do repositório
   e baixe **F1-Clash-Coach.apk**.
   - Alternativa: aba **Actions** → *Gerar APK Android* → última execução → *Artifacts* → baixe o ZIP com o APK.
3. Abra o APK e permita *Instalar apps desconhecidos* para o navegador/gerenciador de arquivos.
4. As próximas versões instalam por cima (mesma assinatura), sem perder seus dados.

## Primeiro uso

1. **Ajustes (⚙️)** → escolha a IA e cole a chave:
   - **Claude** (padrão, melhor análise de prints + busca web): crie em `console.anthropic.com` → API Keys.
   - **Gemini** (tem plano gratuito com limite): crie em `aistudio.google.com` → Get API key.
2. **Início → ▶ Iniciar Coach**. Permita *Sobrepor a outros apps* e a captura de tela. O F1 Clash abre com o painel por cima.
3. No jogo, abra cada peça e piloto uma vez: a garagem se preenche sozinha (confira na aba Garagem).
4. Informe moedas/série tocando nos números da Garagem.
5. Jogue normalmente. O painel pode ser arrastado pelo título e recolhido com um toque.

Dica: o painel usa `FLAG_SECURE`, então ele não aparece na própria captura (o OCR nunca confunde as dicas do Coach com o jogo).

## Como funciona por dentro

```
core/   (Kotlin puro, testado)               app/   (Android)
├─ model/   peças, pilotos, pistas            ├─ capture/  MediaProjection + ML Kit OCR + overlay
├─ engine/  ScreenReader  (lê a tela)         ├─ data/     armazenamento local (JSON) e ajustes
│           LiveCoach     (dicas ao vivo)     ├─ news/     notícias públicas (jsoup)
│           RaceStrategist + WearModel        └─ ui/       telas em Jetpack Compose
│           SetupOptimizer / UpgradePlanner
│           HistoryAnalyzer
└─ ai/      ClaudeAdvisor (SDK oficial Anthropic) · GeminiAdvisor (REST) · prompts
```

Princípios:
- **Corrida não depende de internet**: as dicas ao vivo vêm do motor local (milissegundos). A IA entra quando você pede.
- **Nada é inventado**: números vêm da sua tela, dos seus registros ou de você. Pesos de pista e desgaste são estimativas iniciais, recalibradas com seus dados.
- **Privacidade**: OCR roda no aparelho; só vai para a IA o que você mandar (pergunta, print, resumo da conta).

## Desenvolvimento

- Build: `./gradlew :core:test :app:assembleRelease` (o workflow `.github/workflows/build-apk.yml` faz isso a cada push).
- O módulo `core` não depende do Android: dá para testar a lógica em qualquer máquina com JDK 17.
- `keystore/coach.jks` é a chave de assinatura de uso pessoal (senha `f1clashcoach`), mantida no repo para que todo build instale por cima do anterior. Não use para publicar na Play Store.

Veja também [ARCHITECTURE.md](ARCHITECTURE.md), [ANDROID_ONLY.md](ANDROID_ONLY.md) e [SOURCES.md](SOURCES.md).
