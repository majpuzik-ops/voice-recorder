# Voice Recorder AI

Real-time streaming hlasový překladač pro Android.

## Jak to funguje

```
┌─────────────┐    audio stream     ┌─────────────────┐
│   Mobil     │ ──────────────────► │     Server      │
│  (Android)  │     WebSocket       │   (AI/Whisper)  │
│             │ ◄────────────────── │                 │
└─────────────┘   text + překlad    └─────────────────┘
                    paralelně
```

1. **Nahrávání** - audio se streamuje živě přes WebSocket na server
2. **Server** - paralelně zpracovává:
   - Whisper/STT přepisuje řeč → originální text
   - Překladač překládá → přeložený text
3. **Mobil** - přijímá oba texty v reálném čase a zobrazuje je

## Funkce

- **Real-time přepis** - živý převod řeči na text
- **Real-time překlad** - okamžitý paralelní překlad
- **TTS** - text-to-speech přehrání překladu
- **Nahrávání hovorů** - automatické nahrávání telefonních hovorů
- **Cover Display** - zobrazení překladu pro protistranu (Samsung Fold)
- **Split Display** - rozdělená obrazovka pro oba účastníky
- **Floating okno** - plovoucí překlad přes jiné aplikace
- **Tailscale** - připojení k serveru přes VPN

## Použití

- Konverzace s cizincem - oba vidí překlad okamžitě
- Cover display na Fold - protistrana čte překlad na vnějším displeji
- Nahrávání hovorů s překladem

## Build

```bash
~/maj-app-workflow.sh ~/voice-recorder-app <verze>
```
