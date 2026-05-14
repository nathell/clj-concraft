# Concraft – Guesser

```clojure
^{:nextjournal.clerk/visibility {:code :hide, :result :hide}}
(ns guesser
  (:require [concraft.polish :as pl]
            [concraft.model :as model]
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
(-> m :guesser :crf :ob-codec :to inv-map (get 1))
```

Właściwie powinniśmy zaglądać do pola `:from`, nie `:to`, ale akurat dla kodeku `:ob-codec` mapa `:from` jest pusta (mniejsza o to, dlaczego).

A etykiety tak:

```clojure
(-> m :guesser :crf :label-codec :from (get 1))
```

Czyli po prostu `praet:sg:perf:m1` w notacji poliqarpowej.

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
