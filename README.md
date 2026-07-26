# AUREA Android 0.1

Prototipo nativo per il tablet P90.

## Funzioni incluse

- avatar AUREA Preview 05 incorporato e utilizzabile offline;
- dashboard Home Assistant nella metà destra;
- parola di attivazione `Aurea`, senza toccare lo schermo;
- stati visivi RIPOSO, ASCOLTO, ELABORAZIONE e PARLATO;
- invio del comando testuale all'API Conversation di Home Assistant;
- risposta vocale tramite la voce italiana installata nel tablet;
- schermo intero e display mantenuto acceso mentre AUREA è aperta.

## Sicurezza

La configurazione richiede un token Home Assistant dedicato ad AUREA. Non
inserire token nel codice sorgente e non riutilizzare quello personale.

## Limiti della versione 0.1

- il riconoscimento dipende dal servizio vocale Android presente sul P90;
- la risposta usa temporaneamente la sintesi vocale Android, non ancora Piper;
- il labiale usa il motore automatico della Preview 05 e non i tempi fonetici
  reali della traccia audio;
- l'ascolto resta attivo soltanto mentre AUREA è visibile in primo piano.

Questi limiti sono intenzionali: la prima prova deve verificare stabilità,
parola di attivazione e comunicazione con Home Assistant prima di aggiungere
Piper e sincronizzazione fonetica.
