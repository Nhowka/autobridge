# AutoBridge

🇺🇸 [Read in English](README.md)

**Dê internet à central multimídia do carro usando o seu celular – pela mesma conexão Wi-Fi que o Android Auto sem fio
já usa.** Sem roteador no celular, sem chip extra: enquanto o Android Auto está conectado, os apps da central (mapas,
música, atualizações…) acessam a internet pelos dados móveis do celular.

Um app pequeno (≈1,3 MB) para os dois lados. Você instala **só no celular** – o próprio celular instala e configura o
app na central pelo cabo USB.

> Feito e testado num Jaecoo J7 (central Desay SV, Android 11) com um celular Android recente. Outras centrais Android
> que permitem ADB devem funcionar, mas não foram testadas.

## Baixar
Baixe `AutoBridge-<versão>.apk` em **[Releases](../../releases)** e instale no celular (permita "instalar apps
desconhecidos" para o navegador ou gerenciador de arquivos quando pedir).

## Configurar o carro pelo USB (uma vez, e a cada atualização)
1. **Ative o ADB na central.** Nas centrais Chery / Jaecoo / Omoda (Desay SV) é uma chave no menu de engenharia /
   depuração; a porta USB de dados passa a funcionar no modo ADB (dispositivo).
2. **Conecte o celular de modo que ele seja o host USB:** um adaptador OTG USB-C no celular mais um cabo USB-A para
   USB-A até a porta do carro (ou um cabo USB-C se a porta do carro for USB-C). Se o celular só começar a carregar, ele
   não é o host – use o adaptador OTG.
3. O Android pergunta se quer **abrir o AutoBridge** para o dispositivo USB – toque em OK (ou abra o AutoBridge e toque
   em **Configurar carro (USB)**, que aparece enquanto o carro está conectado). Permita o acesso ao USB.
4. Toque em **Configurar carro**. O AutoBridge se instala na central (também nas que bloqueiam o `pm install`), libera
   a VPN, define a central como o lado do carro e o abre. Se a central perguntar *"Permitir depuração USB?"*, marque
   *Sempre permitir*.
5. **Desative o ADB de novo** na central, para os pendrives voltarem a funcionar e ninguém mais usar o ADB.

Para atualizar o carro depois: atualize o AutoBridge no celular e repita os passos 1–5 – *Configurar carro* atualiza a
central para a versão do celular.

## Usar
No carro, com o Android Auto sem fio conectado, abra o AutoBridge no celular e toque em **Compartilhar internet**.
Marque *Iniciar automaticamente quando o carro conectar* para começar sozinho em toda viagem (o Android pede acesso ao
Bluetooth e para deixar o AutoBridge rodar em segundo plano). A notificação do celular mostra quantos dados o carro
usou; a central mostra *Internet: do seu celular*.

## Como funciona
- **Celular:** um proxy SOCKS5 no endereço do celular no Wi-Fi do carro (senha nova a cada sessão), que envia o tráfego
  pela rede de dados móveis. A cada 20 s ele se oferece ao carro.
- **Carro:** uma VPN que envia o tráfego de internet da central (só endereços públicos – os aparelhos do próprio Wi-Fi do
  carro continuam locais) para esse proxy, usando o
  [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel). Ela para cerca de um minuto depois que o celular
  deixa de se oferecer.
- **Configuração pelo USB:** o AutoBridge tem um cliente ADB próprio. Ele copia o próprio APK para a central e instala
  pelo PackageInstaller do Android (um pequeno auxiliar rodado com `app_process`, código em `installer-dex/`), porque
  algumas centrais recusam o `pm install`.

## Bom saber
- **Dados:** tudo o que a central baixar conta nos dados móveis do celular.
- **Uma VPN por vez:** o Android permite só uma VPN na central; não use outro app de VPN nela enquanto compartilha.
- **Sem pareamento, de propósito:** o carro aceita a oferta de internet de qualquer aparelho no próprio Wi-Fi dele. Esse
  Wi-Fi tem senha e normalmente só o seu celular (Android Auto) está nele.
- Sem vínculo com Google, Android Auto, Chery, Jaecoo, Omoda ou Desay SV.

## Compilar
    ./gradlew assembleRelease
Precisa do Android SDK (compileSdk 37) e do NDK 28.2. A saída vai para `~/.gradle-builds/AutoBridge/`.
O túnel nativo vem pré-compilado em `app/src/main/jniLibs/` (como: `app/src/main/jniLibs/README.md`).

## Créditos
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) por hev (MIT), com lwIP, hev-task-system e libyaml –
  licenças em `app/src/main/assets/licenses/`.
