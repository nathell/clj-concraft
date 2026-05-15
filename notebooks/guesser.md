# Concraft – Guesser

```clojure
^{:nextjournal.clerk/visibility {:code :hide, :result :hide}}
(ns guesser
  (:require [clojure.string :as str]
            [concraft.polish :as pl]
            [concraft.model :as model]
            [concraft.crf.chain1 :as chain1]
            [nextjournal.clerk :as clerk]))
```

## Preliminaria

Najpierw załadujmy sobie model. Zakładamy, że jest w bieżącym katalogu.

```clojure
(def m (model/load-model "concraft-pl-model-SGJP-20220221.gz"))
```

I zdefiniujmy prostą funkcję do odwracania map na lewą stronę.

```clojure
(defn inv-map [m]
  (zipmap (vals m) (keys m)))
```

I jeszcze do konwertowania tagu do notacji poliqarpowej.

```clojure
(defn tag->poliqarp [{:keys [pos atts]}]
  (str/join ":" (into [pos] (vals atts))))
```

## `compute-psi`

`compute-psi` jest wywoływana 8 razy przy przetwarzaniu przykładowego inputu „Zatrzasnął drzwi od mieszkania” – 2x per krawędź DAG-u? Wartości `int-obs` i `labels` są takie dla poszczególnych wywołań:

```clojure
^{:nextjournal.clerk/visibility {:code :hide}}
(clerk/table
 (clerk/use-headers
  [["" "int-obs" "labels"]
   [1 [0 1 2 3 4 5] [1 2 3]]
   [2 [6 7 8 9 4 10] [4 5 6 7]]
   [3 [11 12 13 14 4 10] [8 9]]
   [4 [15 16 17 18 4 10] [10 11 12 13 14 15 16]]
   [5 [0 1 2 3 4 5] [1 2 3]]
   [6 [6 7 8 9 4 10] [4 5 6 7]]
   [7 [11 12 13 14 4 10] [8 9]]
   [8 [15 16 17 18 4 10] [10 11 12 13 14 15 16]]]))
```

(TODO: powyższa tabelka jest chwilowo zahardkodowana w notebooku; chcę wymyślić jakiś dobry sposób na pożenienie Clerka z scope-capture, żeby móc z poziomu notatnika powiedzieć „wstaw mi `spy` w takie a takie miejsce, uruchom ten kod, a potem udostępnij clerkowi skapczerowane wartości”.)

A więc owszem: `args(1) = args(5)`, etc. Zapewne raz w _forward pass_ i drugi raz w _backward pass_. `int-obs` za każdym razem jest 6-krotką, jak rozumiem, kodującą prefiksy, sufiksy i kształt słowa (1-prefiks, 2-prefiks, 1-sufiks, 2-sufiks, czy słowo jest w leksykonie, czy zaczyna zdanie + kształt). Wektor `labels` zaś jest zmiennej długości, zawsze posortowany i zawiera numery etykiet dla danej krawędzi, czyli po prostu tagów (bez form podstawowych).

Jak wydłubać z modelu opis obserwacji / etykiety? Obserwacji tak:

```clojure
(-> m :guesser :crf :ob-codec :to inv-map (get 3))
```

Właściwie powinniśmy zaglądać do pola `:from`, nie `:to`, ale akurat dla kodeku `:ob-codec` mapa `:from` jest pusta (mniejsza o to, dlaczego).

A etykiety tak:

```clojure
(-> m :guesser :crf :label-codec :from (get 1) tag->poliqarp)
```

### Ficzery

Teraz potrzebuję opowiedzieć sobie o _ficzerach_. Ficzery to to, czemu model przypisuje wagi; Claude nazwał je „the discriminative model's vocabulary”. W guesserze (`chain1.clj`) mamy trzy rodzaje ficzerów:

- **SFeature(lb)** („start”) – „etykieta _lb_ pojawiła się na pierwszej krawędzi”
- **TFeature(lb1, lb2)** („transition”) – „etykieta _lb1_ poprzedza etykietę _lb2_”
- **OFeature(ob, lb)** („observation”) – „_lb_ etykietuje krawędź z obserwacją _ob_”

Zobaczmy teraz, ile i jakich ficzerów zna łącznie model guessera:

```clojure
(->> m :guesser :crf :model :ix-map keys (map :type) frequencies)
```

`:ix-map` mapuje opisy ficzerów (zawierające `:type`, `:ob`, `:lb`, `:lb1`, `:lb2`) na numery.

### Z powrotem do `compute-psi`

Jestem teraz prawie gotowy, żeby zrozumieć działanie `compute-psi`. Ta funkcja operuje na o-ficzerach i używa wektora `:ob-ixs-v`. On odpowiada na pytanie: „dla danej obserwacji, które o-ficzery ją zawierają i z jakimi etykietami ją wiążą?” Na przykład:

```clojure
(-> m :guesser :crf :model :ob-ixs-v (get 3))
```

Tylko sześć o-ficzerów! A oto etykiety, jakie im odpowiadają:

```clojure
(let [from (-> m :guesser :crf :label-codec :from)]
  (map (comp tag->poliqarp from) [1 2 3 205 206 207]))
```

To ma sens. Przypomnijmy, że obserwacja 3 oznacza sufiks _-ął_; powyższy wynik mówi nam, że tak – zdaniem modelu – kończą się tylko pseudoimiesłowy przeszłe któregoś z trzech rodzajów męskich, niezależnie od aspektu. Dla kontrastu, prefiksy nie dają takiej dystynktywności. Dla prefiksu "z-" możliwych tagów jest znacznie więcej:

```clojure
(-> m :guesser :crf :model :ob-ixs-v (get 0) count)
```

Sygnatura (intuicyjna) `compute-psi` to: `krawędź -> (etykieta -> double)`. Czyli: dla danej krawędzi (z obserwacjami i etykietami) zwraca wektor liczb („potencjał obserwacyjny”) indeksowany numerami etykiet.

Co robi `compute-psi`? Dla każdej z sześciu obserwacji danej krawędzi w grafie wydłubuje wszystkie o-ficzery, których etykiety pokrywają się z tymi obserwacjami, i dla każdego z nich dodaje wartość ficzeru z modelu do psi dla jego etykiety.

Na przykład dla krawędzi „zatrzasnął”:

```clojure
(-> m :guesser :crf :model (#'chain1/compute-psi [0 1 2 3 4 5] [1 2 3]) vec)
```

Intuicyjnie, im większe psi, tym silniej podejrzewamy daną krawędź na tym etapie o bycie otagowaną daną etykietą. Czyli „Zatrzasnął” to raczej m1 niż m2/m3.

A oto rozpiska, co dokładnie robi to wywołanie:

```text
Labels: 3
Observation: 0
O-features: 369 matching: [[0 41943] [1 41944] [2 41945]]
Feature: 41943 log-value: 4.782616200214538
Feature: 41944 log-value: 6.5442739581355145
Feature: 41945 log-value: 7.145586983821059
Observation: 1
O-features: 310 matching: [[0 42312] [1 42313] [2 42314]]
Feature: 42312 log-value: -0.5778809585237595
Feature: 42313 log-value: -0.07928422056100885
Feature: 42314 log-value: -1.9575920195503587
Observation: 2
O-features: 58 matching: [[0 42622] [1 42623] [2 42624]]
Feature: 42622 log-value: 14.032743538968761
Feature: 42623 log-value: -1.2060000698410505
Feature: 42624 log-value: 4.579294887566764
Observation: 3
O-features: 6 matching: [[0 42680] [1 42681] [2 42682]]
Feature: 42680 log-value: 1.722841009844102
Feature: 42681 log-value: -0.10530347078541123
Feature: 42682 log-value: 0.39232764618058213
Observation: 4
O-features: 1012 matching: [[0 42686] [1 42687] [2 42688]]
Feature: 42686 log-value: -8.28744791902551
Feature: 42687 log-value: -1.2060000698410505
Feature: 42688 log-value: -3.0077640407023294
Observation: 5
O-features: 312 matching: [[0 43698] [2 43699]]
Feature: 43698 log-value: -0.4118736170616058
Feature: 43699 log-value: -1.3562592038416856
```

Ciekawe! Z tego wynika, że zdaniem modelu końcówka `-ł` (obserwacja 2) silnie sugeruje m1, a za to fakt, że słowo jest modelowi znane (obserwacja 4) silnie _penalizuje_ m1, ale nie aż tak, żeby w ostatecznym rozrachunku m1 nie wygrał.
