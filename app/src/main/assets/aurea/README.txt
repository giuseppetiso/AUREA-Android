AUREA — Rig Preview 05
======================

PROVA RAPIDA
1. Estrai completamente lo ZIP.
2. Apri la cartella aurea_rig_preview_05.
3. Fai doppio clic su index.html.
4. Premi "Movimento testa" oppure "Avvia demo".

NOVITÀ DELLA PREVIEW 05
- Fondale, testa, capelli e busto sono livelli distinti.
- Il volto originale C1 non è stato rigenerato.
- Testa, occhi, palpebre e bocca si muovono come un unico rig registrato.
- Il busto e lo sfondo restano immobili.
- Micro-inclinazione massima limitata a 0,8 gradi.
- Piccoli spostamenti automatici diversi per riposo, ascolto e parlato.
- Movimento secondario dei capelli quasi impercettibile.
- Respiro leggero disattivato automaticamente in modalità risparmio.
- Tutte le funzioni della Preview 04 restano disponibili:
  battito a cinque pose, sguardo naturale e dodici visemi italiani.
- Pausa completa quando la pagina non è visibile.

MODALITÀ TABLET / HOME ASSISTANT
- Solo avatar:
  index.html?mode=avatar
- Solo avatar e risparmio:
  index.html?mode=avatar&lowPower=1

API JAVASCRIPT DISPONIBILE
- AUREA.setState("idle" | "listen" | "speak")
- AUREA.setGaze(x, y)
- AUREA.setHeadPose(x, y, roll)
- AUREA.headMotion()
- AUREA.setViseme("REST" | "MBP" | "A" | "E" | "I" | "O" | "U" |
                   "FV" | "L" | "SZ" | "CH" | "CNS")
- AUREA.playVisemeTrack([{ at: 0, viseme: "MBP" }, ...])
- AUREA.blink()
- AUREA.playDemo()
- AUREA.stopDemo()
- AUREA.setLowPower(true | false)
- AUREA.getState()
- AUREA.getMetrics()

NOTE
- Questa è la prova tecnica della separazione 2,5D.
- La rifinitura estetica generale verrà eseguita alla fine del progetto.
- La voce Isabella non è ancora collegata.
- I file immagine devono restare nella cartella assets.
