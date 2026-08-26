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

AUREA pubblica inoltre la telemetria tecnica necessaria alle automazioni del
tablet, senza immagini, audio o testo delle conversazioni:

- `sensor.aurea_tablet_system`: rete, volume, luminosità, spazio e uptime;
- `sensor.aurea_tablet_battery`: percentuale, ricarica, sorgente e temperatura;
- `binary_sensor.aurea_tablet_screen`: stato dello schermo;
- `sensor.aurea_tablet_active_profile`: profilo locale verificato.

## AUREA Presence

Quando la dashboard è visibile, la fotocamera frontale rileva localmente una
persona davanti al tablet a bassa frequenza. Non salva né pubblica fotogrammi.
Lo stato è disponibile come `binary_sensor.aurea_tablet_person_in_front`.
La luminosità viene ridotta dopo 50 secondi senza volto o interazione e torna
subito normale al rilevamento o al tocco. Da 45 °C la fotocamera viene sospesa
automaticamente e riparte quando il tablet torna a una temperatura sicura.

## AUREA Identity passiva

Quando AUREA Presence rileva un volto sufficientemente vicino e frontale,
allinea occhi, naso e bocca, verifica luce e nitidezza e confronta localmente
un embedding SFace specifico per il riconoscimento facciale più una firma
strutturale con dodici campioni diversificati
per profilo. Servono più corrispondenze consecutive; i casi dubbi restano
`sconosciuto`. Home
Assistant riceve soltanto il risultato tramite
`sensor.aurea_tablet_recognized_person`, mai fotografie o firme biometriche.
Il sensore è destinato alla personalizzazione e non autorizza portoni, allarmi,
pagamenti o altre azioni sensibili. Il riconoscimento può essere disattivato
separatamente da Strumenti AUREA.

La verifica vocale v2 individua automaticamente inizio e fine della frase,
scarta campioni troppo rumorosi, bassi o saturi e confronta quattro modelli
locali di timbro e prosodia. Un risultato appena sotto soglia richiede due
campioni coerenti invece di fallire immediatamente. La diagnostica conserva
soltanto qualità, punteggio e soglia: mai audio, immagini o firme biometriche.

Se il vecchio modello del volto non permette più di aprire Gestione persone,
Giuseppe può usare il recupero tramite la propria voce già registrata e, dopo
la conferma, acquisire un nuovo volto. La fotocamera passiva dispone inoltre di
un controllo automatico che riavvia il flusso se non arrivano più fotogrammi.
Se anche la firma vocale non coincide più, un secondo recupero genera un codice
temporaneo visibile soltanto nelle notifiche dell'Home Assistant configurato.
Il codice resta esclusivamente in memoria, scade dopo cinque minuti, consente
al massimo cinque tentativi e viene invalidato dopo l'uso.

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
