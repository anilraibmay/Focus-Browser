# ABYS Toolkit — scripts prontos

Esquema comprovado (abyss.to / `.fd` / `/sora/`):

- `.fd` = **parte 1** do MP4. Apenas os **primeiros 0x10000 (65536) bytes** estao cifrados.
- Cifra: **AES-256-CTR**, `key = ASCII(md5hex(basename_do_fd))` (32 bytes), `IV = key[0:16]`.
- Partes 2..N sao **continuacao plana** do mesmo `mdat` (sem cifra) — concatenacao de bytes basta.
- Rota `/sora/<sizeId>/<token>` e **virtual (Service Worker)**; `token = b64(b64(AES_CTR(path?query, md5hex(sizeId))))`.
- O `?v=<slug>` do `/mp4/...` e o **slug publico**, nao um token de sessao.
- `.fd` **exige `Referer: https://abysscdn.com/`** (sem isso: 403 Cloudflare).

---

## Nivel 1 — decifrar / analisar

### `abys_decrypt.js` — decifrador standalone
```
node abys_decrypt.js <url-ou-arquivo.fd> [out.mp4]
```
Decifra os 64KB de cabecalho e grava MP4 jogavel (parte 1).

### `abys_meta.js` — extrator de metadados/indice
```
node abys_meta.js <url-ou-arquivo.fd> [--name <basename.fd>] [--json meta.json]
```
Le todo o `moov` (tracks, codecs, resolucao, duracao, stsz). **Um unico part de 20MB ja traz o indice do filme inteiro.**

### `abys_lib.js` — nucleo reutilizavel (biblioteca)
Exporta `md5hex`, `keyMaterial`, `decryptFd`, `tokenEncrypt`, `httpGet`, `readBoxes`, `findBox`, `walkBoxes`, `DEFAULT_HEADERS`.

### `abys_token.js` — gerador/decodificador de token `/sora`
```
node abys_token.js gen "/mp4/23557934/4/232643771?v=oQDjHTSeQ" 232643771
node abys_token.js dec "<token>" 232643771
```
Reproduz **byte a byte** o token capturado no service worker (`MATCH: true`).

---

## Nivel 2 — mapear / montar

### `abys_locate.js` — tempo -> byte absoluto -> numero da parte
```
node abys_locate.js <file.fd|url> --name <basename.fd> [--at 60] [--part 3] [--part-size 20971520] [--json out.json]
```
Valida em 50s/60s/2900s. `parte N = bytes [N*partSize, (N+1)*partSize)`.

### `abys_assemble.js` — concatenador de partes -> MP4 unico
```
node abys_assemble.js --name <basename.fd> --out movie.mp4 \
     --part 1=part1.fd --part 2=./parts/part2.bin --part 3=https://host/...fd
node abys_assemble.js --name <basename.fd> --dir ./parts --out movie.mp4
```
Roundtrip md5 validado.

### `abys_mkparts.js` — gera fixture de teste (split em partes de 20MB)
```
node abys_mkparts.js      # le fixture_src.mp4 -> ./parts/partN.bin
```

### `abys_grab.js` — coleta ponta-a-ponta (urls.txt -> baixa -> decifra -> monta)
```
node abys_grab.js urls.txt [--ref https://abysscdn.com/] [--out ep04.mp4] [--raw] [--name <basename.fd>]
```
`urls.txt`: uma URL por linha; aceita sufixo `|bytes=0-...` para Range. Valida ao vivo (20MB -> `moov@28 mdat@862062`).

---

## Nivel 3 — consumir

### `abys_local_server.js` — servidor HTTP com Range para streaming
```
node abys_local_server.js --name <basename.fd> [--plain] [--port 8787] \
     --part 1=part1.fd [--part 2=./parts/part2.bin | --part 2=https://...] [--dir ./parts]
```
Endpoints: `/` player · `/movie.mp4` video (206/416) · `/status` JSON. Validado com ffprobe + seek.

---

## Captura no browser

### `abys_capture.user.js` — capturador (Tampermonkey ou console)
Instale em `abysscdn.com` / `*.abyss.to` / `embedplayabyss.top`. Apos assistir/arrastar o video:
- `__abys.list()` → lista URLs `/mp4/`, `.fd`, `/sora/`
- `__abys.cache()` → dump do Cache Storage (URL, Range, tamanho, status)
- `__abys.grab()` → baixa todos os blobs de midia do cache
- `__abys.save()` → salva `abys_urls.txt`

Gere `urls.txt` e rode `abys_grab.js` — pipeline 100% offline depois disso.

---

## Pipeline completo
```
player (slug) ─▶ abys_capture.user.js ─▶ abys_urls.txt
                                          └─▶ abys_grab.js ─▶ ep.mp4
                                                  │
                          abys_meta / abys_locate / abys_local_server (validar/streaming)
```

---

## Nivel 2 (extra) — reconhecimento de RESOLUCOES

### `abys_qualities.js` — enumera resolucoes / codecs / espelhos
```
node abys_qualities.js media_plain.json [--probe] [--duration 2978.8] [--json]
```
Le o JSON `media` do player e produz a arvore `resolucao -> codecs -> variantes -> espelho`,
com tamanho, **bitrate estimado** (precisa de `--duration`) e, com `--probe`, um HEAD ao vivo
nos `fristData` para confirmar a entrega. `--json` emite tudo em JSON para consumo por script.

**Mapa observado (amostra 720p/360p):**

| resolucao | res_id | codec | variantes | partSize | obs |
|---|---|---|---|---|---|
| 360p | 2 | h264 | 2 (~118 MB, 0.33 Mbps) | - | 2 espelhos |
| 360p | 2 | av1  | 1 (~92 MB, 0.26 Mbps) | 2 MB | fristData OK (206) |
| 720p | 4 | h264 | 3 (222 MB / 640 MB / 639 MB) | 20 MB | fristData OK (206) |

Notas importantes:
- `res_id` e **numerico** (2=360p, 4=720p nesta amostra; a tabela completa 0..7 esta no script).
- A MESMA resolucao tem **varias variantes de bitrate** (720p: 0.62 vs 1.80 Mbps) — nao basta
  escolher por resolucao; escolha por tamanho/bitrate.
- `partSize` **varia por codec** (20 MB no h264, 2 MB no av1). Leia sempre do `fristData`.
- Cada variante aponta um **espelho** (`sub`), e o `fristData` traz a URL da parte 1.
- `status:true` = variante disponivel.

## Nomeacao correta do arquivo final (Serie / Temporada / Episodio)

### Descoberta central (validada ao vivo 26/09/2026)
A pagina do player `https://abysscdn.com/?v=<slug>` entrega, **renderizado no servidor**,
o `<title>` = **nome original do arquivo enviado**, ex.:

| slug | `<title>` |
|------|-----------|
| `oQDjHTSeQ` | `A.Shop.for.Killers.S01E01.720p.MP4.LEG.BaixarSeriesMP4.pm.mp4` |
| `2q7oyEYTh` | `Loja.Para.Assassinos.S01E01.Murthehelp.1080p.DSNP.WEB-DL.DDP5.1.H.264.DUAL-SiGLA_EMBEDPLAY.mp4` |

Esse titulo ja contem **Serie + SxxExx + resolucao + release**. E o **unico** lugar, do lado
do provedor, onde existe a numeracao de temporada/episodio.
O JSON de `media` (sources/qualities) **NAO** tem season/episode — so `label/res_id/codec/size/sub`.

A mesma pagina tambem traz `const datas = "<base64>"`, que decodifica para
`{"slug":..,"md5_id":..,"user_id":..,"media":<cifrado>}`. O `md5_id` identifica o episodio no
CDN (aparece em `/mp4/<md5_id>/...`).

### IMPORTANTE: Cloudflare
`abysscdn.com` esta atras do Cloudflare e responde **403 challenge ("Just a moment...")** para
clientes **Node/undici**. O **`curl`** do sistema passa. Por isso `abys_name.js` usa o binario
`curl` (existe no Win10+, Linux e macOS) em vez de `https.get`.

### Novo script: `abys_name.js`
```bash
node abys_name.js <slug|player-url|embed-url> [--json]
```
Saida (teste real):
```
slug               : oQDjHTSeQ
titulo original    : A.Shop.for.Killers.S01E01.720p.MP4.LEG.BaixarSeriesMP4.pm.mp4
md5_id             : 23557934 (user_id 9133)
serie              : A Shop for Killers
temporada/episodio : S01E01 (padrao: SxxExx)
resolucao (no nome): 720p
NOME FINAL         : A Shop for Killers - S01E01 - 720p.mp4
PASTA              : A Shop for Killers
```
Formatos S/E reconhecidos: `S01E01`, `s01.e01`, `1x01`, `Temporada 1 Episodio 2`, `Ep 12`
(assume temporada 1).

### Downloader com nome automatico: `abys_grab.js`
```bash
node abys_grab.js urls.txt --slug oQDjHTSeQ --dir ./downloads
# -> ./downloads/A Shop for Killers/A Shop for Killers - S01E01 - 720p.mp4
#    + ./downloads/A Shop for Killers/A Shop for Killers - S01E01 - 720p.json  (sidecar)
```
- `--slug`  : consulta `abys_name.js` e deriva pasta+nome.
- `--dir`   : raiz de saida (padrao: `.`).
- `--flat`  : ignora a subpasta da serie.
- `--out`   : forca um nome exato (desliga o automatico).
- Se o `urls.txt` ja tiver `# slug=` / `# title=` (o userscript grava), dispensa o `--slug`.
- Colisao de nome (ex.: dois releases do mesmo EP) -> sufixo com o `md5_id`.

### Userscript `abys_capture.user.js` (v2)
Passou a capturar tambem os metadados de nomeacao. Na pagina do player:
```js
__abys.meta()      // {url, slug, title, md5_id, user_id}
__abys.save()      // salva <slug>_urls.txt com cabecalho # title/ # slug/ # md5_id/ + URLs
__abys.manifest()  // JSON de todos os episodios navegados (slug -> metadados)
__abys.grab()      // baixa os .fd/ /sora/ que estao no Cache Storage
```
O `urls.txt` salvo ja entra direto no `abys_grab.js`, que le o cabecalho e nomeia sem flags.

### Cuidados
- O `<title>` e o **nome do upload original**, nao o transcode. O nome pode dizer `1080p`
  enquanto as variantes reais sao `360p/720p`. Confirme com `abys_qualities.js` / `abys_meta.js`
  (que le `width x height` do proprio video).
- O titulo pode vir em outro idioma (ex.: "Loja Para Assassinos") conforme o site de origem.
- Nao ha protection extra: a temperatura `Referer` continua sendo necessaria para o `.fd`.

---

## Baixar o EPISODIO INTEIRO continuamente (sem pegar so a 1a parte)

### O problema
O `fristData` entrega **apenas a parte 1** (ex.: 20 MB de um arquivo de 232.643.771 bytes = 12 partes).
Baixar so o `fristData` => MP4 **truncado** (por isso as ferramentas de verificacao existem).
As partes 2..N vivem atras da rota virtual `/sora/<size>/<token>` do **Service Worker**.

### O que NAO funciona (testado)
- **Replay do token `/sora/` no CLI**: o token e reproduzivel byte a byte
  (`abys_token.js` => `MATCH: true`), porem **replay direto retorna 404** em TODOS os hosts
  (g98ooid4p28, 3vzuayxd22, i34mbcqo17, ivjn18ppq1, 1bkanh6i30, pzt9ldgwf52), com ou sem
  `Referer`/`Origin`/`Range`. O `/sora/` so existe **dentro do Service Worker** (nao e um endpoint
  real de CDN).
- **Node/undici**: bloqueado por TLS-fingerprint do Cloudflare (403). `curl` passa (200).

### A solucao: "browser SW-oracle"
Em vez de reverter cada derivacao de token, usamos o **proprio Service Worker como oraculo**.
Com a pagina do player aberta, o SW expoe uma **URL virtual** (ex.:
`https://<origin>/mp4/<md5_id>/<res_id>/<size>?v=<slug>`) que responde a **Range requests**
com `206` + `content-range: bytes X-Y/<total>`. Dentro do navegador, o `fetch` passa pelo
Cloudflare normalmente. Entao fazemos um **loop de Range** cobrindo `0..size-1`, verificando
cada resposta, e concatenamos num unico `.mp4`.

Logica do segment builder (extraida do SW, para referencia):
```
chunkSize = 0x200000 (2 MiB)
totalParts = ceil(size / chunkSize)
startPart  = floor(offset / chunkSize)
para a parte N: key = hash(pathname) + '/' + N;  token = tokenEncrypt(pathname+'/'+N+search, size)
                url = origin + '/sora/' + size + '/' + token
```

### `abys_pull.user.js` — downloader completo no navegador
Instale em `abysscdn.com` / `embedplayabyss.top` / `*.sssrr.org`. Abra o player, deixe o video
rodar alguns segundos (para capturar a URL virtual), e use o painel flutuante ou o console:

```js
__abys_pull.meta()          // {url, slug, title, md5_id, user_id}
__abys_pull.candidatos()    // URLs virtuais /mp4|/part|/r2 capturadas
await __abys_pull.detectar()// escolhe a URL virtual + descobre o TOTAL (content-range)
const r = await __abys_pull.baixarTudo()  // loop de Range; r.faltas = partes que falharam
const mp4 = __abys_pull.montar(r)         // Uint8Array unico (falha se cob. incompleta)
__abys_pull.verificar(mp4)                // {ok, tamanho, ftyp, moov, mdat}
await __abys_pull.salvar(mp4)             // nomeia com <title> e salva (disco se disponivel)

// atalho que faz tudo:
await __abys_pull.auto()
```

Garantias contra "so a primeira parte":
1. Descobre o **total** pelo `content-range` da 1a resposta (nao assume um valor fixo).
2. Faz loop por TODAS as partes; cada resposta e checada (`206`, tamanho exato); falhas viram
   retry (6x) e, se persistirem, sao registradas em `r.faltas`.
3. So chama `montar`/`salvar` quando a **cobertura == total** e a assinatura `ftyp` existe.
4. Salva usando `showSaveFilePicker` (streaming em disco) quando disponivel; senao Blob.

### `abys_verify.js` — verificador CLI de integridade
```bash
node abys_verify.js <arquivo.mp4> [--size <bytes>] [--chunks manifest.json] [--json]
```
- Confere **tamanho** vs `--size`.
- Parseia os boxes de topo (`ftyp/moov/mdat/moof`) e detecta **truncamento**: se o `mdat` declara
  mais bytes do que o arquivo tem, sinaliza `TRUNCADO` (exatamente o sintoma de "so a 1a parte").
- Com `--chunks`, calcula a **cobertura de bytes** `0..size-1` e lista **buracos (gaps)**.
- Exit code != 0 quando falha (util para scripts de retomada / CI).

Manifest aceito:
```json
{ "total": 232643771, "parts": [ {"start":0,"end":20971519,"ok":true}, ... ] }
```

Exemplo real (`media_full.mp4`, primeira parte de um arquivo de 232 MB):
```
boxes topo   ftyp > moov > mdat
avisos       mdat declara 231781713 bytes em offset 862058 => fim 232643771 > 20971520 (TRUNCADO)
RESULTADO    FALHOU
```

### Pipeline recomendado (integrado)
```
player / link ─▶ abys_pull.user.js (navegador, SW ativo)
                    │  loop Range + montagem + ftyp/moov check
                    └─▶ <title>.mp4   (Serie.SxxExx.res.mp4, nome do <title>)
                        └─▶ abys_verify.js <title>.mp4 --size <total>   (auditoria final)

offline/arquivado ─▶ abys_capture.user.js ─▶ urls.txt ─▶ abys_grab.js ─▶ ep.mp4
                        └─▶ abys_verify.js / abys_meta.js / abys_qualities.js
```

### Limitacoes
- `abys_pull.user.js` **depende do SW ativo** (rode na propria pagina do player, nao copie a URL).
- Se a URL virtual nao aparecer, reproduza o video (buffer) e verifique `__abys_pull.candidatos()`.
- Sem `showSaveFilePicker` (Firefox antigo), o arquivo inteiro fica em memoria antes de salvar.
- O `<title>` e o **nome do upload**, podendo divergir do transcode real (confirme com `abys_meta.js`).
