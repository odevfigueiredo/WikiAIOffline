# Pesquisa e validação do acesso local

O WikiAI Offline 0.7.0 usa o Android como servidor temporário do próprio acervo. O navegador recebe a interface e os resultados pela rede local. O APK inclui a interface, a arte e os pesos da IA. Um computador, tablet ou segundo celular pode abrir o painel sem instalar outro aplicativo ou configurar um modelo.

## Decisões de implementação

| Área | Decisão e motivo |
| --- | --- |
| Rede sem internet | O aplicativo procura um IPv4 privado em Wi-Fi ou Ethernet, inclusive em uma rede sem acesso à internet. O QR contém o endereço literal e o código é informado à parte. Isso evita depender de DNS, descoberta por multicast ou serviço externo. O Android 16 permite testar antecipadamente as restrições de acesso à LAN. O guia do Android descreve a permissão em tempo de execução para apps voltados à API 37 em Android 17. [Acesso à rede local](https://developer.android.com/privacy-and-security/local-network-permission) · [Estado da rede](https://developer.android.com/develop/connectivity/network-ops/reading-network-state) |
| Ciclo de vida | O usuário inicia o acesso no Android e pode encerrá-lo a qualquer momento. A sessão termina ao sair da tela do aplicativo ou quando o endereço utilizado deixa de estar disponível. A operação em segundo plano exigiria um fluxo distinto, com notificação persistente e avaliação do tipo de serviço em primeiro plano. [Tipos de serviço em primeiro plano](https://developer.android.com/develop/background-work/services/fgs/service-types) |
| Pareamento | O código de oito dígitos expira em cinco minutos. O servidor restringe tentativas, usa um cookie de sessão e confere `Host` e `Origin`. Renovar o código revoga as sessões atuais. O painel usa HTTP e o tráfego pode ser lido na rede. Use-o em uma rede privada de confiança. [Gerenciamento de sessões](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) · [Verificação de origem](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#using-standard-headers-to-verify-origin) |
| Acervo e leitura | O Android abre o ZIM pelo seletor de arquivos. O painel reaproveita o catálogo e a busca já usados no aplicativo, mostra título, descrição, idioma, data e edição quando disponíveis e abre a fonte como texto. Remover um item no painel desvincula o acervo e remove a eventual cópia privada do app. O arquivo de origem permanece no dispositivo. [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) · [libzim](https://github.com/openzim/libzim) · [java-libkiwix](https://github.com/kiwix/java-libkiwix) |
| Busca e latência | **Pesquisar** retorna os trechos sem iniciar o modelo. A ação de IA reaproveita o fluxo nativo, com carregamento sob demanda do LFM2.5 350M e uma referência ao trecho utilizado. Se a geração falhar, o painel preserva os resultados. A síntese envolve um modelo pequeno e continua sujeita à qualidade do texto recuperado. [Cartão do LFM2.5 350M](https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF) |

## Melhorias a medir

1. **Busca em acervos grandes.** Medir tempo até o primeiro trecho, percentis 50 e 95 da busca, memória máxima e diferenças entre acervos com e sem índice de texto completo. Testar arquivos com vários gigabytes, fornecedores de armazenamento distintos e consultas em português e inglês. [libzim](https://github.com/openzim/libzim)
2. **IA em aparelhos ARM64.** Medir partida fria, busca com modelo já carregado, tempo até o primeiro token e taxa de geração. Registrar memória e quantas respostas citam o trecho correto em um conjunto fixo de perguntas. Essas medidas dão critérios para avaliar quantização e tamanho da janela de contexto. [llama-bench](https://github.com/ggml-org/llama.cpp/blob/master/tools/llama-bench/README.md)
3. **Transferência e disponibilidade na LAN.** Antes de aceitar arquivos pelo navegador, validar transferência em fluxo, espaço livre, cancelamento, retomada e validação do ZIM antes de incluir o arquivo no catálogo. Se houver demanda por acesso contínuo, validar o serviço em primeiro plano e as permissões nas versões recentes do Android. [Documentos Android](https://developer.android.com/training/data-storage/shared/documents-files) · [Serviços em primeiro plano](https://developer.android.com/develop/background-work/services/fgs/service-types)

## Validação

Os testes locais e as capturas correspondem à versão 0.7.0. O registro da versão 0.6.0 permanece no [relatório histórico](../TEST_REPORT.txt).

| Cenário | Resultado |
| --- | --- |
| Testes automatizados | Cinco testes JUnit passaram, incluindo três do servidor. O script Kotlin verificou busca bilíngue, ranqueamento, referências e resposta numérica. |
| Build e Lint | O APK de depuração ARM64 foi gerado e sua assinatura v3, seu alinhamento e o SHA-256 do GGUF foram verificados. O Android Lint registrou zero erros e 28 avisos. |
| Android e rede local | A jornada usou um emulador x86_64 com Android 16 e o Edge no computador. O Wi-Fi do Android manteve o IPv4 privado, sem rede validada e sem conexão TCP ao destino público da sonda. O navegador chegou ao servidor do APK por uma ponte de teste do emulador que preservou o endereço e os cabeçalhos HTTP. |
| APK ARM64 em modo avião | O APK final executou por tradução de ABI no Android 16, com `primaryCpuAbi=arm64-v8a` e sem rede padrão ativa. O log confirmou o carregamento do modelo por `mmap`. Uma nova consulta no aplicativo retornou as duas substâncias em português, com `[1]`. |
| Catálogo e busca | O seletor abriu acervos ZIM reais de teste. A interface exibiu título e descrição e usou o nome de arquivo quando o título faltou. Após parear, o navegador pesquisou a fotossíntese e abriu o texto original em inglês. |
| Síntese | A pergunta sobre os dois materiais usados na fotossíntese gerou `Fotosíntese utiliza água e dióxido de carbono. [1]`. Na execução do APK final x86_64, o trecho apareceu em cerca de 0,2 s e a síntese em cerca de 1,7 s. A medição é do emulador e inclui a espera do teste no navegador. |
| Sessão e remoção | O catálogo respondeu `401` antes do pareamento e após **Sair**. Uma recarga manteve a sessão autenticada. Cancelar a remoção preservou o catálogo. Confirmar removeu o item de teste e manteve o SHA-256 do ZIM original. |
| Ciclo de vida | O botão Home encerrou o servidor. Ao voltar ao Android, o painel continuou desligado até a reativação manual. O novo código permitiu parear outra vez. |
| Layout | Capturas do Edge em 1440 × 980 e 390 × 844, além da janela nativa do Android. Em testes de interface com respostas de teste, Edge e Firefox percorreram as telas a 320 px sem rolagem horizontal. |

A comparação em aparelhos físicos e em redes domésticas, além de testes com ZIMs de vários gigabytes, fica para validações futuras.

SHA-256 do APK ARM64 desta compilação local: `56961D759CA47E67AE80398FB35028F39B21508322BDACDCD5089A0D10D5CFFD`.

## Varredura após os testes

- A revisão do encerramento encontrou um intervalo entre esconder o endereço e invalidar as sessões. A revogação agora ocorre antes de atualizar a interface, e a reativação aguarda o encerramento do servidor anterior. O monitor acompanha a rede Android e o IPv4, inclusive quando a rede muda e o IPv4 é atribuído novamente.
- A busca preserva os trechos quando outro navegador já usa a IA. A verificação de formato também aceita respostas exclusivamente numéricas com referência, como um ano seguido de `[1]`.
- A remoção do catálogo informa quando a exclusão da cópia privada fica pendente. O painel mantém o catálogo atualizado após a remoção confirmada.
- Foram alinhados os rótulos de ativação do Android e do painel web, o foco após a síntese e o anúncio de resultados em leitores de tela. No README, foram conferidos a versão, o nome do artefato, as permissões de rede, as instruções e as referências da galeria.

## Arte do painel

O painel carrega a [ilustração WebP](../app/src/main/assets/lan/library-art.webp) incluída no APK. A galeria do README mostra capturas da interface em execução.
