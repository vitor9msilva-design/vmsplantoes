# Plantões (Android)

App nativo em Kotlin (Views, sem Compose), pacote `br.med.plantoes`, minSdk 26, compile/target 34.
O usuário é médico, não programador: fale em português simples.

- Dados: JSON em `filesDir/plantoes.json` (`Store` em `Plantao.kt`). Alarmes: `Alarms.kt` (setAlarmClock, 24 h e 2 h antes).
- Cores por hospital: `Paleta` em `Util.kt` (guardadas nas preferências; nunca coloque nomes de hospitais no código).
- Não coloque a escala pessoal do usuário (hospitais, dias, horários) no código: este repositório é público.
- Sincronização com a página web: arquivo JSON `{"formato":"plantoes-v1","plantoes":[{local,setor,data,hora,dur,valor,obs}]}`.
- Atualização automática: `Atualizador.kt` lê `release/versao.json` e baixa `release/Plantoes.apk` (branch main).
- Para publicar: siga "Publicar uma versão nova" no README. Sempre aumente `versionCode`, assine com a chave privada (`app/plantoes.jks` + `app/keystore.properties`, fora do git; peça ao usuário se não estiver no ambiente)
  e confira com `aapt2 dump badging release/Plantoes.apk`. Rode `./gradlew testReleaseUnitTest` antes.
- Interface: `Telas.kt` monta cada aba (Início, Agenda, Calendário, Livres, Ajustes) a partir de `Dados`; `Ui.kt` tem as peças visuais;
  `Tema.kt` tem os temas claros. Sempre tema claro.
- Para ver as telas sem celular: `./gradlew recordPaparazziDebug` gera PNGs em `app/src/test/snapshots/images/` (fora do git),
  a partir de `TelasShot.kt` (dados de exemplo com hospitais fictícios). Olhe as imagens antes de publicar mudanças visuais.
- Financeiro (`Financeiro.kt`): resumo por mês (`resumoMes`), linha do tempo com `MesesChart`, análises automáticas.
  Rendas fixas (`Fixos`/`RendaFixa`), recebimentos, metas e backup ficam nas preferências do celular, nunca no código.
- O app NÃO altera configurações do celular: não mexe no volume, não fura o "Não perturbe", não pede isenção de bateria.
  Notificações vibram uma vez (sem FLAG_INSISTENT); a tela de alarme toca no máximo 1 minuto. Pedido explícito do usuário.
