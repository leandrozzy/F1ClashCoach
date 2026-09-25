# F1 Clash AI Coach — 100% Android

Você não precisa de Windows, PC ou Android Studio.

## Caminho recomendado: GitHub Actions pelo navegador do Android

1. No Android, abra github.com e crie um repositório privado chamado `F1ClashCoach`.
2. Envie todo o conteúdo desta pasta para a raiz do repositório, mantendo a pasta `.github/workflows`.
3. Abra a aba **Actions** do repositório.
4. Abra **Gerar APK Android**.
5. Toque em **Run workflow**.
6. Quando finalizar, abra a execução concluída.
7. Em **Artifacts**, baixe **F1-Clash-AI-Coach-APK**.
8. Abra o ZIP baixado e instale `F1-Clash-AI-Coach.apk`.
9. Se o Android bloquear a instalação, permita **Instalar apps desconhecidos** somente para o navegador/gerenciador usado.

O APK é compilado na nuvem. O celular é usado apenas para enviar o projeto, iniciar o build, baixar e instalar.

## Atualizações futuras

Quando o código for atualizado no GitHub, um novo build é disparado automaticamente no branch `main`. Também é possível executar manualmente pelo botão **Run workflow**.

## Plano B: compilar no próprio Android com Termux

É tecnicamente possível, porém não é a opção principal deste projeto. Requer Android ARM64, alguns GB livres, JDK, Gradle, Android SDK e um AAPT2 compatível com ARM. Para uso cotidiano, prefira o GitHub Actions.
