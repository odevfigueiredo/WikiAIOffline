<img width="100%" src="https://capsule-render.vercel.app/api?type=waving&amp;color=0:0B1220,55:0D9488,100:2563EB&amp;height=190&amp;section=header&amp;text=WikiAI%20Offline&amp;fontSize=50&amp;fontColor=ffffff&amp;animation=fadeIn&amp;fontAlignY=35&amp;desc=Conhecimento%20e%20IA%20local%2C%20mesmo%20sem%20internet&amp;descAlignY=58&amp;descSize=17" alt="WikiAI Offline" />

<div align="center">
  <p><strong>Consulte acervos ZIM em português e inglês e obtenha respostas com inteligência artificial executada diretamente no Android.</strong></p>
  <p>
    <img src="https://img.shields.io/badge/Status-Em_desenvolvimento-2563EB?style=for-the-badge" alt="Projeto em desenvolvimento" />
    <img src="https://img.shields.io/badge/Vers%C3%A3o-0.6.0-0D9488?style=for-the-badge" alt="Versão 0.6.0" />
    <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Android 13 ou superior" />
    <img src="https://img.shields.io/badge/Arquitetura-ARM64-2563EB?style=for-the-badge" alt="Arquitetura ARM64" />
    <img src="https://img.shields.io/badge/IA-No_dispositivo-0F766E?style=for-the-badge" alt="Inteligência artificial no dispositivo" />
    <img src="https://img.shields.io/badge/Acervo-ZIM_local-111827?style=for-the-badge" alt="Acervo ZIM local" />
  </p>
  <p>
    <a href="https://github.com/odevfigueiredo/WikiAIOffline/actions/workflows/build-apk.yml">APK de teste</a> ·
    <a href="./INSTRUCOES_PTBR.txt">Guia rápido</a> ·
    <a href="./TEST_REPORT.txt">Relatório de testes</a>
  </p>
</div>

---

## 📖 Sobre

O **WikiAI Offline** é um aplicativo Android para pesquisar conteúdos de arquivos **ZIM** sem depender de uma conexão com a internet. Ele combina a consulta a acervos locais em português e inglês com um modelo de linguagem executado no próprio dispositivo.

Na versão **0.6.0**, o APK para **ARM64** já inclui os pesos do modelo multilíngue **LFM2.5 350M**. Os arquivos ZIM são adicionados separadamente pelo usuário, permitindo escolher quais bibliotecas manter no aparelho.

A IA prepara uma resposta curta usando o trecho mais relevante dos acervos abertos. As referências permitem conferir os resultados no texto original.

> A utilização dos acervos e do modelo é local. A obtenção inicial dos arquivos ZIM e das dependências de compilação pode exigir acesso à internet.

---

## ✨ Funcionalidades

- **Leitura de acervos ZIM** com título e descrição quando esses dados estão no arquivo.
- **Busca local** por títulos e palavras-chave. A busca por título continua disponível em acervos sem índice de texto completo.
- **IA integrada:** geração de respostas curtas usando o modelo **LFM2.5 350M** incluído no APK.
- **Carregamento sob demanda:** o modelo é carregado na primeira consulta que precisa de geração.
- **Pesquisa multilíngue:** possibilidade de traduzir termos de busca para inglês quando a busca inicial não encontra trechos e há um acervo nesse idioma.
- **Fontes consultáveis** com trechos exibidos antes da síntese e acesso ao texto original por referências numeradas.
- **Preservação dos resultados:** os trechos continuam disponíveis caso a geração seja interrompida, ultrapasse o tempo ou produza referências inválidas.
- **Catálogo e histórico locais:** os dados de uso permanecem armazenados no dispositivo.
- **Modelos personalizados:** gerenciamento de modelo já instalado em **Acervo offline → Opções avançadas**.
- **Tratamento de acesso a arquivos:** cópia automática do ZIM, com progresso, quando o Android não permite manter o acesso direto ao arquivo selecionado.

---

## 🧱 Tecnologias

<div align="center">
  <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&amp;logo=kotlin&amp;logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&amp;logo=gradle&amp;logoColor=white" alt="Gradle" />
  <img src="https://img.shields.io/badge/Android_NDK-0F766E?style=for-the-badge" alt="Android NDK" />
  <img src="https://img.shields.io/badge/CMake-064F8C?style=for-the-badge&amp;logo=cmake&amp;logoColor=white" alt="CMake" />
  <img src="https://img.shields.io/badge/ZIM-Offline-2563EB?style=for-the-badge" alt="Arquivos ZIM" />
  <img src="https://img.shields.io/badge/GGUF-Modelo_local-334155?style=for-the-badge" alt="Modelo GGUF local" />
</div>

---

## 📱 Como começar

### 1. Instale o aplicativo

Instale o APK em um smartphone ou tablet **ARM64** com **Android 13 ou superior**. O pacote `offline` de teste tem **306 MB** e já incorpora o modelo de linguagem para a geração local.

1. Entre na sua conta do GitHub e abra uma execução concluída com sucesso. [GitHub Actions do WikiAI Offline](https://github.com/odevfigueiredo/WikiAIOffline/actions/workflows/build-apk.yml)
2. Em **Artifacts**, baixe `wikiai-offline-v0.6.0-arm64-debug-apk` e extraia o APK.
3. Transfira o APK para o aparelho e instale-o.

O GitHub Actions mantém esse artefato por **14 dias**. A seção de compilação explica como gerar uma cópia local.

### 2. Adicione uma biblioteca ZIM

1. Transfira um arquivo `.zim` para o aparelho ou obtenha um acervo no [catálogo do Kiwix](https://library.kiwix.org/).
2. No WikiAI Offline, toque em **Abrir arquivo ZIM**.
3. Selecione o arquivo pelo seletor do Android.
4. Confira o título e a descrição quando estiverem no ZIM. Se faltar o título, o aplicativo exibe o nome do arquivo. Quando o Android exigir uma cópia, o progresso aparece na interface. A cópia ocupa espaço adicional no aparelho.

> Arquivos disponíveis apenas na área privada de outro aplicativo precisam ser disponibilizados pelo seletor de arquivos do Android antes de serem abertos no WikiAI Offline.

### 3. Pesquise e consulte as fontes

1. Digite uma pergunta em português sobre o conteúdo do acervo.
2. Confira os trechos exibidos conforme a busca os encontra.
3. Quando necessário, aguarde o carregamento do modelo para gerar uma resposta curta a partir do trecho mais relevante.
4. Toque na referência numerada para consultar o texto original.

A marcação **[1]** identifica a fonte do trecho enviado à IA. Os demais resultados permanecem disponíveis para leitura.

### 4. Gerencie o modelo

Em **Acervo offline → Opções avançadas**, é possível trocar ou remover um modelo personalizado que já tenha sido instalado no dispositivo.

---

## 🗂️ Estrutura do projeto

```text
WikiAIOffline/
├── app/src/main/                  App Android, interface e integração local
├── gradle/                        Configuração do Gradle e revisão do modelo
├── patches/                       Ajustes do llama.cpp para Android
├── tests/                         Testes Kotlin de busca e resposta
└── .github/workflows/build-apk.yml CI e geração do APK de teste
```

[Arquitetura do WikiAI Offline](ARQUITETURA_PTBR.txt)

---

## ⚙️ Compilação

### Requisitos

| Componente | Versão / requisito |
|---|---|
| Git | Instalado |
| JDK | **17** |
| Android SDK Platform | **36** |
| Android Build Tools | **36.0.0** |
| Android NDK | **29.0.13113456** |
| CMake | **3.31.6** |
| Arquitetura padrão do APK | **ARM64 (arm64-v8a)** |

No Windows, utilize um caminho com caracteres ASCII, como `C:\Projetos\WikiAIOffline`, para evitar problemas com ferramentas nativas de compilação.

### Windows

```powershell
.\PREPARAR_IA_WINDOWS.bat
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleOfflineDebug
```

### Linux e macOS

```bash
bash PREPARAR_IA_LINUX_MAC.sh
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleOfflineDebug
```

### APK gerado

O resultado ARM64 da compilação de depuração é salvo em:

```text
app/build/outputs/apk/offline/WikiAI-Offline-v0.6.0-arm64-v8a-debug.apk
```

Para compilar para um emulador **x86_64**, acrescente o parâmetro `-PwikiAbis=x86_64` ao comando Gradle.

A tarefa `assembleOfflineDebug` incorpora e alinha o modelo no APK para acesso direto e realiza as verificações de integridade e assinatura previstas no processo de compilação.

### Compilação após preparação offline

Na primeira compilação, o projeto baixa dependências e o arquivo **GGUF** correspondente à revisão fixada do modelo. Com os componentes necessários já em cache, é possível compilar sem acessar a rede utilizando `--offline` e apontando para um modelo previamente verificado:

```bash
bash gradlew --offline -PwikiModelFile=/caminho/para/modelo.gguf :app:testDebugUnitTest :app:lintDebug :app:assembleOfflineDebug
```

O processo de build verifica o **tamanho** e o **SHA-256** do modelo informado. O caminho do exemplo deve ser substituído pelo arquivo real.

---

## 🧪 Testes e qualidade

Na verificação local da versão 0.6.0, os **dois testes JUnit** passaram e o Android Lint registrou **zero erros e 35 avisos**. Em modo avião, a consulta a um ZIM em inglês gerou uma resposta em português com referência no emulador x86_64 e no teste do APK ARM64 por tradução de ABI.

Falta medir o desempenho em aparelho ARM64 físico e com acervos de vários gigabytes. [Relatório de verificação](TEST_REPORT.txt)

O projeto disponibiliza testes Kotlin e tarefas de verificação pelo Gradle:

```bash
bash gradlew :app:testDebugUnitTest :app:lintDebug
```

Também é possível executar os scripts específicos:

**Windows**

```powershell
.\tests\executar_tests_windows.bat
```

**Linux e macOS**

```bash
bash tests/executar_tests_linux_mac.sh
```

### GitHub Actions

O workflow prepara o ambiente Android, executa os testes e o Lint e disponibiliza o APK ARM64 assinado para teste. [Configuração do workflow](.github/workflows/build-apk.yml)

> A compilação em CI requer acesso à internet para obter o SDK, as dependências e o arquivo do modelo. A possibilidade de uso offline refere-se ao aplicativo instalado e aos acervos armazenados no dispositivo.

---

## 🔐 Privacidade e limitações

- **Execução local** com pesquisa e inferência no dispositivo. O APK verificado opera sem a permissão `INTERNET`.
- **Acervo separado** do APK. Cada biblioteca ZIM é obtida e adicionada pelo usuário.
- **Respostas geradas** podem omitir informações ou acrescentar detalhes ausentes do trecho. A referência indica o contexto usado pela IA e abre a fonte para conferência.

---

## 📚 Referências

- [Biblioteca do Kiwix — acervos ZIM](https://library.kiwix.org/)
- [LFM2.5 350M GGUF — cartão do modelo](https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF)
- [Licenças e fontes incluídas no projeto](LICENCAS_E_FONTE.txt)

---

<div align="center">
  <p>Desenvolvido por <a href="https://github.com/odevfigueiredo">Jonatha Figueiredo</a></p>
  <img width="100%" src="https://capsule-render.vercel.app/api?type=waving&amp;color=0:2563EB,45:0D9488,100:0B1220&amp;height=120&amp;section=footer" alt="" />
</div>
