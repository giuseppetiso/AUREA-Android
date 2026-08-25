# AUREA Android

Prototipo nativo per il tablet P90.

## Funzioni incluse

- avatar AUREA Preview 05 incorporato e utilizzabile offline;
- dashboard Home Assistant nella metà destra;
- parola di attivazione `Aurea`, senza toccare lo schermo;
- stati visivi RIPOSO, ASCOLTO, ELABORAZIONE e PARLATO;
- invio del comando testuale all'API Conversation di Home Assistant;
- risposta vocale tramite la voce italiana installata nel tablet;
- schermo intero e display mantenuto acceso mentre AUREA è aperta.

## Monitor diagnostico automatico

AUREA Diagnostics 2.0 controlla il tablet e l'integrazione con Home Assistant
ogni 30 minuti, anche quando la schermata Diagnostics non è aperta. Pubblica:

- `sensor.aurea_tablet_diagnostics`, con esito, conteggio problemi e riepilogo
  sanificato;
- `sensor.aurea_tablet_heartbeat`, con l'ultimo controllo riuscito.

Le anomalie nuove o cambiate vengono affidate a
`script.aurea_registra_anomalia`, che conserva il rapporto e usa il canale
email configurato in Home Assistant. La stessa anomalia non viene ripetuta
prima di 12 ore. Il ripristino genera una comunicazione e, in condizioni
regolari, viene inviata al massimo una email riepilogativa al giorno.

Il tablet conserva soltanto URL e token Home Assistant già richiesti da AUREA:
nessuna credenziale email viene memorizzata nell'app.

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
