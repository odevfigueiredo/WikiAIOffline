<img width="100%" src="https://capsule-render.vercel.app/api?type=waving&amp;color=0:0B1220,55:0D9488,100:2563EB&amp;height=190&amp;section=header&amp;text=WikiAI%20Offline&amp;fontSize=50&amp;fontColor=ffffff&amp;animation=fadeIn&amp;fontAlignY=35&amp;desc=Conhecimento%20e%20IA%20local%2C%20mesmo%20sem%20internet&amp;descAlignY=58&amp;descSize=17" alt="WikiAI Offline" />

<div align="center">
  <p><strong>Consulte acervos ZIM em português e inglês e obtenha respostas com inteligência artificial executada diretamente no Android.</strong></p>
  <p>
    <img src="https://img.shields.io/badge/Vers%C3%A3o-0.6.0-0D9488?style=for-the-badge" alt="Versão 0.6.0" />
    <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Android 13 ou superior" />
    <img src="https://img.shields.io/badge/Arquitetura-ARM64-2563EB?style=for-the-badge" alt="Arquitetura ARM64" />
    <img src="https://img.shields.io/badge/IA-No_dispositivo-0F766E?style=for-the-badge" alt="Inteligência artificial no dispositivo" />
    <img src="https://img.shields.io/badge/Acervo-ZIM_local-111827?style=for-the-badge" alt="Acervo ZIM local" />
  </p>
</div>

---

## 📖 Sobre o projeto

O **WikiAI Offline** é um aplicativo Android para pesquisar conteúdos de arquivos **ZIM** sem depender de uma conexão com a internet. Ele combina a consulta a acervos locais em português e inglês com um modelo de linguagem executado no próprio dispositivo.

Na versão **0.6.0**, o APK para **ARM64** já inclui os pesos do modelo multilíngue **LFM2.5 350M**. Os arquivos ZIM são adicionados separadamente pelo usuário, permitindo escolher quais bibliotecas manter no aparelho.

As respostas são fundamentadas em trechos recuperados do acervo selecionado, com referências para consulta aos artigos originais.

> A utilização dos acervos e do modelo é local. A obtenção inicial dos arquivos ZIM e das dependências de compilação pode exigir acesso à internet.

---

## ✨ Funcionalidades

- **Leitura de acervos ZIM:** abertura de arquivos compatíveis em português e inglês diretamente do armazenamento do Android.
- **Busca local:** pesquisa por títulos e palavras-chave no índice dos arquivos ZIM.
- **IA integrada:** geração de respostas curtas usando o modelo **LFM2.5 350M** incluído no APK.
- **Carregamento sob demanda:** o modelo é carregado na primeira consulta que precisa de geração.
- **Pesquisa multilíngue:** possibilidade de traduzir termos de busca para inglês quando a busca inicial não encontra trechos e há um acervo nesse idioma.
- **Fontes consultáveis:** apresentação dos trechos encontrados e acesso ao artigo original por referências numeradas.
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

## 📱 Como usar

### 1. Instale o aplicativo

Instale o APK em um smartphone ou tablet **ARM64** com **Android 13 ou superior**. O pacote `offline` já incorpora o modelo de linguagem necessário para a geração local.

### 2. Adicione uma biblioteca ZIM

1. Transfira um arquivo `.zim` para o aparelho ou obtenha um acervo no [catálogo do Kiwix](https://library.kiwix.org/).
2. No WikiAI Offline, toque em **Abrir arquivo ZIM**.
3. Selecione o arquivo pelo seletor do Android.
4. Aguarde a leitura do título e da descrição do acervo. Caso seja necessária uma cópia local, acompanhe o progresso na interface.

> Arquivos disponíveis apenas na área privada de outro aplicativo precisam ser disponibilizados pelo seletor de arquivos do Android antes de serem abertos no WikiAI Offline.

### 3. Pesquise e consulte as fontes

1. Digite uma pergunta, por exemplo, em português.
2. Confira os trechos exibidos conforme a busca os encontra.
3. Quando necessário, aguarde o carregamento do modelo para gerar uma resposta curta a partir do trecho mais relevante.
4. Toque na referência numerada para consultar o texto original.

A marcação **[1]** identifica o trecho utilizado como contexto principal da resposta. Os demais resultados permanecem disponíveis para conferência.

### 4. Gerencie o modelo

Em **Acervo offline → Opções avançadas**, é possível trocar ou remover um modelo personalizado que já tenha sido instalado no dispositivo.

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

## 🧪 Testes e validação

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

Para informações adicionais sobre a validação e os componentes distribuídos, consulte:

- [Relatório de verificação](TEST_REPORT.txt)
- [Licenças e fontes dos componentes](LICENCAS_E_FONTE.txt)

### GitHub Actions

O workflow de compilação, quando adicionado ao repositório em `.github/workflows/build-apk.yml`, prepara o ambiente Android, executa testes e Android Lint, compila o pacote offline ARM64 e publica o APK como artefato da execução.

> A compilação em CI requer acesso à internet para obter o SDK, as dependências e o arquivo do modelo. A possibilidade de uso offline refere-se ao aplicativo instalado e aos acervos armazenados no dispositivo.

---

## 🔐 Privacidade e limitações

- **Execução local:** a pesquisa em ZIM e a inferência do modelo incluído são executadas no dispositivo.
- **Acervo separado:** o APK contém o modelo de linguagem, mas **não inclui os arquivos ZIM**. Cada biblioteca deve ser obtida e adicionada separadamente.
- **Respostas verificáveis:** a resposta gerada deve ser conferida nos trechos e artigos citados, especialmente em assuntos críticos.
- **Requisitos do dispositivo:** a distribuição descrita nesta versão requer Android 13+ e arquitetura ARM64.
- **Disponibilidade do conteúdo:** somente informações existentes nos acervos selecionados poderão ser encontradas pela busca local.

---

## 📚 Referências

- [Biblioteca do Kiwix — acervos ZIM](https://library.kiwix.org/)
- [LFM2.5 350M GGUF — cartão do modelo](https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF)
- [Licenças e fontes incluídas no projeto](LICENCAS_E_FONTE.txt)

---

<div align="center">
  <strong>WikiAI Offline</strong><br />
  Conhecimento local. Fontes acessíveis. Independência de conexão.
</div>
