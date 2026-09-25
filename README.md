# Assistente do Paulo (PWA)

App pessoal que instala pela tela inicial do celular (sem loja). Sem build: HTML, CSS e JS puros.

- `index.html` carrega o núcleo e os módulos.
- `nucleo.js`: registro de módulos (`App.registrar`), dados locais (`App.dados`), notificações (`App.notificar`), avisos.
- `modulos/calendario.js`: agenda com lembretes, repetição, áreas da vida (trabalho, família, saúde, lazer) e exportação `.ics`.
- `sw.js`: funciona offline e mostra notificações. Ao criar arquivo novo, adicione na lista `ARQUIVOS` e suba `VERSAO`.

## Novo módulo
1. Crie `modulos/<nome>.js` chamando `App.registrar({ id, nome, icone, montar(el), fundo() })`.
2. Adicione o `<script>` no `index.html` antes de `App.iniciar()`.
3. Adicione o arquivo no `sw.js`. Com 2+ módulos, a barra de abas aparece sozinha.

## Limites dos lembretes
Notificações do navegador tocam com o app aberto ou em segundo plano recente. Para alarme garantido com o app fechado, o botão "Pôr no calendário do celular" gera um `.ics` com alarme. Push de verdade (com app fechado) exige um pequeno servidor; fica para uma próxima etapa.

## Rodar local
`python3 -m http.server` nesta pasta e abra `http://localhost:8000`.

## Módulo Decisões
`modulos/decisoes.js` mostra cartões com opções clicáveis (Agência, Freelance, Pessoal). O assistente escreve as decisões em `dados/decisoes.json` no formato:
`{ "decisoes": [ { "id", "frente": "agencia|freelance|financas|pessoal", "titulo", "contexto", "prazo": "AAAA-MM-DD", "opcoes": [ { "id", "texto", "efeito", "recomendada": true } ] } ] }`
Com a lista vazia, o app mostra cartões de exemplo. As escolhas ficam salvas no aparelho (ainda não voltam para o assistente).
