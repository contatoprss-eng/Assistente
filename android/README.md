# App Android do Assistente Pessoal

Abre o painel (artifact do claude.ai) numa WebView e dá a ele microfone e voz do
próprio Android: `assets/ponte.js` troca `SpeechRecognition` e `speechSynthesis`
pela ponte nativa (`Voz.java`), só nas origens do claude.ai.

- Compila no GitHub Actions (`.github/workflows/apk.yml`) e sai **sem assinatura**
  na release `sem-assinatura`.
- A chave fica fora do repositório. O APK assinado vai para a release `app`:
  https://github.com/contatoprss-eng/Assistente/releases/download/app/AssistentePessoal.apk
- Para atualizar o app no celular, a assinatura tem de ser sempre a mesma chave.
