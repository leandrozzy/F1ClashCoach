# F1 Clash AI Coach — Android only

Aplicativo Android de uso pessoal para funcionar como copiloto do F1 Clash.

## Regra do projeto

**Nenhuma etapa exige Windows ou PC.** O desenvolvimento/build pode ser feito pela nuvem a partir do navegador do Android e o uso é totalmente no celular.

## O que o app faz

- Inicia o F1 Clash a partir do Coach.
- Solicita a autorização de captura de tela do Android (MediaProjection).
- Lê localmente textos/elementos visíveis usando ML Kit OCR.
- Mantém um overlay flutuante com recomendações durante a corrida.
- Reconhece dados como série, circuito, volta e informações de pneus quando estiverem legíveis.
- Mantém estratégias pré-calculadas para não depender da resposta de uma IA externa quando a corrida começa.
- Registra os dados que conseguir extrair da tela de resultado automaticamente.
- Mantém histórico local de corridas.
- Tem modo de varredura da conta: o usuário apenas navega pelas telas do F1 Clash; não precisa gravar e enviar vídeo.
- Atualiza notícias/guias públicos separadamente do motor em tempo real.
- Pode usar IA externa opcional para análise de evolução da conta; o Coach básico não depende dela.

## Como gerar o APK usando apenas Android

Leia **ANDROID_ONLY.md**.

O projeto inclui o workflow:

`.github/workflows/build-apk.yml`

Ele usa uma máquina Linux temporária do GitHub para instalar o SDK necessário, executar testes e gerar `F1-Clash-AI-Coach.apk`. Você inicia e baixa tudo pelo navegador do celular.

## Segurança operacional

O Coach lê a tela e apresenta orientações. Ele não contém auto-clicker nem comandos para controlar automaticamente o F1 Clash.

## Requisitos do app

- Android 8.0+ (`minSdk 26`).
- Permissão de sobreposição para o painel flutuante.
- Autorização de captura de tela quando o Coach for iniciado.
- Internet apenas para notícias/guias/IA; o OCR e a lógica base da corrida foram projetados para funcionar localmente.

## Estado atual

Esta é uma primeira versão técnica. O próximo passo de maior importância é calibrar os detectores/OCR com as telas reais do F1 Clash no aparelho do usuário e ampliar o catálogo de circuitos/estratégias com dados verificados.
