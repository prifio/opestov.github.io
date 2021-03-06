(ns o8v.game
  (:require cljs.reader clojure.string))

(def new-game
  {:node "transition"
  :step 0
  :personal? true :cards-in-hands 1 :return-words? true
  :explanation-tl 20 :guess-tl 3
  :hat #{}
  :players []
  :history {}})

(defonce beep
  (let [e (js/document.createElement "audio")]
    (.setAttribute e "src" "beep.mp3")
    (fn [_] (.play e))))

(defmulti move (fn [model msg] [(:node model) (:tag msg)]))
(defmethod move :default [model msg] model)

(defn next-pair [model]
  (let [p (:players model) n (count p)
        [a b] (:pair model)
        i (mod (inc (count (take-while #(not= a %1) p))) n)
        j (mod (inc (count (take-while #(not= b %1) p))) n)]
    (if (and (:personal? model) (= 0 i))
      (let [j+ (mod (inc j) n)]
        (if (= j+ i)
          [(nth p i) (nth p (mod (inc j+) n))]
          [(nth p i) (nth p j+)]))
      [(nth p i) (nth p j)])))

(defn complete-round [model]
  (let [r? (:return-words? model)
        hands (:hands (:round model))
        return-words (fn [m] (if r? (update m :hat into hands) m))]
    (-> model
      (assoc :node "transition")
      (assoc :pair (next-pair model))
      (return-words)
      (update :history assoc (:step model)
        {:pair (:pair model) :ac (get-in model [:round :ac])
          :rj (if r? '() hands) :hat (if r? hands '())})
      (dissoc :round))))


; {:tag "ac" :word "apple"}
(defmethod move ["explanation" "ac"] [model msg]
  (if-not (some #{(:word msg)} (:hands (:round model)))
    ; ignore word if it is not in the hands
    model
    ; accept word
    (let [r (->
              (:round model)
              (assoc :hands (vec (remove #{(:word msg)} (:hands (:round model)))))
              (update :ac conj (:word msg)))]
      (cond
        ; take new word from the hat
        (seq (:hat model))
        [(assoc model :round r)
          {:cmd "choose" :coll (:hat model)
            :k (min (count (:hat model))
                  (- (:cards-in-hands model) (count (:hands r))))}]
        ; the hat is empty but we have words in the hands
        (seq (:hands r))
        (assoc model :round r)
        ; no more words
        :else
        (complete-round (assoc model :round r))))))

; {:tag "choice" :coll #("car" "map" "bug") :selection '("car" "bug")}
(defmethod move ["explanation" "choice"] [model msg]
  (let [waiting (min (count (:hat model))
        (- (:cards-in-hands model) (count (get-in model [:round :hands]))))]
    (if
      ; we need the specific number of words from the specific collection
      (and (= waiting (count (:selection msg))) (= (:coll msg) (:hat model)))
      (-> model
        (assoc :hat (apply disj (:hat model) (:selection msg)))
        (update-in [:round :hands] into (:selection msg)))
      ; ignore the message for another state
      model)))

; {:tag "tick"}
(defmethod move ["explanation" "tick"] [model msg]
  (cond
    ; continue
    (< (inc (get-in model [:round :ticks])) (:explanation-tl model))
    (update-in model [:round :ticks] inc)
    ; move to the guess state
    (pos? (:guess-tl model))
    [(-> model (assoc :node "guess") (assoc-in [:round :ticks] 0))
      {:cmd "beep"}]
    ; complete round
    :else
    [(complete-round model) {:cmd "beep"}]))

; {:tag "giveup"}
(defmethod move ["explanation" "giveup"] [model msg]
  (complete-round model))

; {:tag "ac" :word "apple"}
(defmethod move ["guess" "ac"] [model msg]
  (if-not (some #{(:word msg)} (:hands (:round model)))
    ; ignore word if it is not in the hands
    model
    ; accept word
    (let [r (->
              (:round model)
              (assoc :hands (vec (remove #{(:word msg)} (:hands (:round model)))))
              (update :ac conj (:word msg)))]
      (if (seq (:hands r))
        (assoc model :round r)
        (complete-round (assoc model :round r))))))

; {:tag "tick"}
(defmethod move ["guess" "tick"] [model msg]
  (if (< (inc (get-in model [:round :ticks])) (:guess-tl model))
    (update-in model [:round :ticks] inc)
    (complete-round model)))

(defmethod move ["transition" "settings"] [model msg]
  (assoc model :node "settings"))
(defmethod move ["settings" "back-from-settings"] [model msg]
  (assoc model :node "transition"))

(defmethod move ["transition" "lastround"] [model msg]
  (assoc model :node "lastround"))
(defmethod move ["lastround" "back-from-lastround"] [model msg]
  (assoc model :node "transition"))

(defmethod move ["settings" "options"] [model msg]
  (assoc model :node "options"))
(defmethod move ["options" "back-from-options"] [model msg]
  (assoc model :node "settings"))

(defmethod move ["settings" "players"] [model msg]
  (assoc model :node "players"))
(defmethod move ["players" "back-from-players"] [model msg]
  (assoc model :node "settings"))

(defmethod move ["settings" "words"] [model msg]
  (assoc model :node "words"))
(defmethod move ["words" "back-from-words"] [model msg]
  (assoc model :node "settings"))

; {:tag "go"}
(defmethod move ["transition" "go"] [model msg]
  (if (and (seq (:hat model)) (:pair model))
    ; to start round we need players and words
    [(-> model
        (assoc :node "explanation")
        (update :step inc)
        (assoc :round {:hands [] :ticks 0}))
      {:cmd "choose" :coll (:hat model)
        :k (min (count (:hat model)) (:cards-in-hands model))}]
    ; otherwise ignore message
    model))

; {:tag "add-words" :coll ["apple" "orange"]}
(defmethod move ["words" "add-words"] [model msg]
  (update model :hat into (:coll msg)))

; {:tag "remove-words" :coll ["apple" "orange"]}
(defmethod move ["words" "remove-words"] [model msg]
  (assoc model :hat (apply disj (:hat model) (:coll msg))))

; {:tag "add-player" :name "Bob"}
(defmethod move ["players" "add-player"] [model msg]
  (if (some #{(:name msg)} (:players model))
    ; if player with this name already exists then ignore the message
    model
    (let [m (assoc (update model :players conj (:name msg)) :personal? true)]
      (if (= 2 (count (:players m)))
        (assoc m :pair (vec (:players m)))
        m))))

; {:tag "remove-player" :name "Bob"}
(defmethod move ["players" "remove-player"] [model msg]
  (if (some #{(:name msg)} (:players model))
    (let [[a b] (:pair model)
          m (assoc model :personal? true :players
              (filterv #(not= (:name msg) %1) (:players model)))]
      (cond
        ; not enough players
        (<= (count (:players model)) 2)
        (dissoc m :pair)

        (= a (:name msg))
        (assoc-in m [:pair 0] (first (remove #(= b %1) (:players m))))
        (= b (:name msg))
        (assoc-in m [:pair 1] (first (remove #(= a %1) (:players m))))

        :else
        m))
    ; if player with this name does not exist then ignore the message
    model))

; {:tag "change-speaker" :name "Bob"}
(defmethod move ["players" "change-speaker"] [model msg]
  (if (some #{(:name msg)} (:players model))
    (let [[a b] (:pair model)
          n (count (:players model))
          i (count (take-while #(not= (:name msg) %1) (:players model)))
          j (rem (+ i (quot n 2)) n)]
      (if (:personal? model)
        (if (= b (:name msg))
          (assoc model :pair [b a])
          (assoc-in model [:pair 0] (:name msg)))
        (assoc model :pair [(:name msg) (nth (:players model) j)])))

    ; if player with this name does not exist then ignore the message
    model))

; {:tag "change-listener" :name "Bob"}
(defmethod move ["players" "change-listener"] [model msg]
  (if (and (:personal? model) (some #{(:name msg)} (:players model)))
    (let [[a b] (:pair model)]
      (if (= a (:name msg))
        (assoc model :pair [b a])
        (assoc-in model [:pair 1] (:name msg))))
    ; new listener must exist and can be changed in pair game only
    model))

; {:tag "lift-player" :name "Bob"}
(defmethod move ["players" "lift-player"] [model msg]
  (if (some #{(:name msg)} (:players model))
    (let [v (:players model)
          i (count (take-while #(not= (:name msg) %1) v))
          v' (if (> i 0) (assoc  v (dec i) (get v i) i (get v (dec i))) v)
          n (count v')
          j (count (take-while #(not= (first (:pair model)) %1) v'))
          k (rem (+ j (quot n 2)) n)]
      (if (:personal? model)
        (assoc model :players v')
        (assoc model :players v' :pair [(nth v' j) (nth v' k)])))
    ; wrong message
    model))

; {:tag "set-explanation-tl" :value 20}
(defmethod move ["options" "set-explanation-tl"] [model msg]
  (assoc model :explanation-tl (:value msg)))

; {:tag "set-guess-tl" :value 5}
(defmethod move ["options" "set-guess-tl"] [model msg]
  (assoc model :guess-tl (:value msg)))

; {:tag "set-return-flag" :value true}
(defmethod move ["options" "set-return-flag"] [model msg]
  (assoc model :return-words? (:value msg)))

; {:tag "set-number-of-cards" :value 2}
(defmethod move ["options" "set-number-of-cards"] [model msg]
  (assoc model :cards-in-hands (:value msg)))

; {:tag "set-personal" :value true}
(defmethod move ["players" "set-personal"] [model msg]
  (if (:value msg)
    (assoc model :personal? true)
    (if (even? (count (:player model)))
      (let [a (first (:pair model))
            n (count (:players model))
            i (count (take-while #(not= a %1) (:players model)))
            j (rem (+ i (quot n 2)) n)]
        (assoc model :personal? false :pair [a (nth (:players model) j)]))
      model)))

; {:tag "fix" :word "pear" :status :ac})
(defmethod move ["lastround" "fix"] [model msg]
  (let [i (:step model) w (:word msg) y (:status msg)]
    (if-let [x (first (filter (fn [k] (some #{w} (((model :history) i) k)))
                        [:ac :rj :hat]))]
      (if (= x y) model
        (let [m (-> model
                  (update-in [:history i x] (partial remove #{w}))
                  (update-in [:history i y] conj w))]
          (cond
            (= x :hat) (update m :hat disj w)
            (= y :hat) (update m :hat conj w)
            :else m)))
      model)))

; {:tag "gameover"}
(defmethod move ["transition" "gameover"] [model msg]
  (assoc model :node "confirm-gameover"))
(defmethod move ["confirm-gameover" "complete-gameover"] [model msg]
  {:node "results" :history (:history model) :personal? (:personal? model)
    :hat (:hat model)})
(defmethod move ["confirm-gameover" "cancel-gameover"] [model msg]
  (assoc model :node "transition"))

; {:tag "reset"}
(defmethod move ["transition" "reset"] [model msg]
  (assoc model :node "confirm-reset"))
(defmethod move ["confirm-reset" "complete-reset"] [model msg]
  new-game)
(defmethod move ["confirm-reset" "cancel-reset"] [model msg]
  (assoc model :node "transition"))

; {:tag "newgame"}
(defmethod move ["results" "newgame"] [model msg]
  new-game)

(defmulti view :node)

(defn- prev-round-info [model]
  (let [step (:step model)
        [a b] (:pair ((:history model) step))
        words (sort (:ac ((:history model) step)))]
    ["In the last round " a " explained "
      (cond
        (= 0 (count words)) ["zero words to " b "."]
        (= 1 (count words)) ["word \"" (first words) \"" to " b "."]
        :else (concat ["to " b " words \""]
                (interpose "\", \"" (drop-last words))
                ["\" and \"" (last words) "\"."]))]))

(defn- who-is-next [model]
  (let [n (count (:hat model))
        [a b] (:pair model)]
    ["The next players are " [:strong a] " and " [:strong b] ". "
    "The hat contains " n " word" (if (= n 1) "" "s") "."]))

(defmethod view "transition" [model]
  (if (= 0 (:step model))
    [[:h1 "New game"]
    (cond
      (and (= 0 (count (:hat model))) (< (count (:players model)) 2))
      [:p "To be able to start the game you need to configure players and
          add words."]
      (= 0 (count (:hat model)))
      [:p "To be able to start the game you need to add words to the hat."]
      (< (count (:players model)) 2)
      [:p "To be able to start the game you need to configure players."]
      :else
      [:p (who-is-next model)])
      (when (and (seq (:hat model)) (> (count (:players model)) 1))
        [:button {:class "go" :onclick (constantly {:tag "go"})} "Go!"])
      [:button {:onclick (constantly {:tag "settings"})} "Settings"]
      [:button {:onclick (constantly {:tag "reset"})} "Reset"]]
    ; else
    [[:h1 (str "Transition " (:step model))]
    [:p
      [(prev-round-info model) " "]
      (cond
        (= 0 (count (:hat model)))
        ["There are no more words in the hat. End the game to view the scores.
        Alternatively you can add words and continue."]
        (< (count (:players model)) 2)
        ["There is not enough players to continue."]
        :else (who-is-next model))]
      (when (and (seq (:hat model)) (> (count (:players model)) 1))
        [:button {:class "go" :onclick (constantly {:tag "go"})} "Go!"])
      [:button {:onclick (constantly {:tag "lastround"})} "Edit last round"]
      [:button {:onclick (constantly {:tag "settings"})} "Settings"]
      [:button {:onclick (constantly {:tag "gameover"})} "End game"]]))

(defmethod view "settings" [model]
  [[:h1
    [:a {:href "#" :class "back"}
      {:onclick (constantly {:tag "back-from-settings"})} "←"]
    "Settings"]
  [:button {:onclick (constantly {:tag "players"})} "Players"]
  [:button {:onclick (constantly {:tag "words"})} "Words"]
  [:button {:onclick (constantly {:tag "options"})} "Options"]])

(defmethod view "words" [model]
  (let [parts (fn [e] (remove clojure.string/blank?
                        (clojure.string/split (.-value (.-target e)) #"\s+")))
        add (fn [e] {:tag "add-words" :coll (parts e)})
        del (fn [e] {:tag "remove-words" :coll (parts e)})]
    [[:h1
      [:a {:href "#" :class "back"}
          {:onclick (constantly {:tag "back-from-words"})} "←"]
      "Words"]
    [:p
      (if (= 1 (count (:hat model)))
        ["There is " [:strong "1"] " word in the hat."]
        ["There are " [:strong (str (count (:hat model)))] " words in the hat."])
      " Enter new ones here (separate them by space)."]
    [:input {:class "textbox" :type "text" :onchange add}]]))

(defmethod view "players" [model]
  (let [opt (fn [a] (fn [x] [:option (when (= a x) {:selected true}) x]))
        val (fn [e] (.-value (.-target e)))
        parts (fn [e] (remove clojure.string/blank?
                        (clojure.string/split (val e) #"\s+")))
        name (fn [e] (-> e (.-target) (.-parentNode)
                        (.-firstChild) (.-innerText)))]
    [[:h1
      [:a {:href "#" :class "back"}
        {:onclick (constantly {:tag "back-from-players"})} "←"]
      "Players"]
    (when (seq (:players model))
      [:ul {:class "p5s-ul"}
        (for [x (:players model)]
          [:li {:class "p5s-li"}
            [:label {:class "p5s-t"} x]
            [:button {:class "p5s-up"}
              {:onclick (fn [e] {:tag "lift-player" :name (name e)})}
              "↑"]
            [:button {:class "p5s-del"}
              {:onclick (fn [e] {:tag "remove-player" :name (name e)})}
              "×"]])])
    [:p "Enter unique names of a new players (separate them by space)"]
    [:input {:class "textbox" :type "text"
              :onchange (fn [e] (mapv (fn [x] {:tag "add-player" :name x})
                                  (parts e)))}]
    (when-let [[a b] (:pair model)]
      [[:p "Game type:"]
      [:select (when (odd? (count (:players model))) {:disabled ""})
        {:onchange (fn [e] {:tag "set-personal"
                            :value (= "Personal" (val e))})}
        [:option (when-not (:personal? model) {:selected true}) "Pair"]
        [:option (when (:personal? model) {:selected true}) "Personal"]]
      [:p "Who explain in the next round?"]
      [:select {:class "dropdown"}
        {:onchange (fn [e] {:tag "change-speaker" :name (val e)})}
        (map (opt a) (:players model))]
      [:p "Who guess in the next round?"]
      [:select (when (not (:personal? model)) {:disabled ""})
        {:class "dropdown"}
        {:onchange (fn [e] {:tag "change-listener" :name (val e)})}
        (map (opt b) (remove #(= a %1) (:players model)))]])]))

(defmethod view "options" [model]
  (let [opt (fn [a] (fn [x] [:option (when (= a x) {:selected true}) x]))
        handler (fn [key f]
                  (fn [e] {:tag key :value (f (.-value (.-target e)))}))]
    [[:h1
      [:a {:href "#" :class "back"}
        {:onclick (constantly {:tag "back-from-options"})} "←"]
      "Options"]
    [:p "Round duration:"]
    [:select {:onchange (handler "set-explanation-tl" js/parseInt)}
      (map (opt (:explanation-tl model)) (range 5 41 5))]
    [:p "Extra seconds for a guess:"]
    [:select {:onchange (handler "set-guess-tl" js/parseInt)}
      (map (opt (:guess-tl model)) (range 0 6 1))]
    [:p "Number of cards in hands:"]
    [:select {:onchange (handler "set-number-of-cards" js/parseInt)}
      (map (opt (:cards-in-hands model)) (range 1 3 1))]
    [:p "Return words to the hat"]
    [:select {:onchange (handler "set-return-flag" (fn [x] (= "Yes" x)))}
      (map (opt (if (:return-words? model) "Yes" "No")) ["Yes" "No"])]]))

(defmethod view "lastround" [model]
  (let [step (:step model)
        jfn (fn [x] (juxt identity (constantly x)))
        words (sort (concat
                (map (jfn :ac) (get-in model [:history step :ac]))
                (map (jfn :hat) (get-in model [:history step :hat]))
                (map (jfn :rj) (get-in model [:history step :rj]))))
        opt (fn [desc x y] [:option {:value (name x)}
                              (when (= x y) {:selected true}) desc])
        handler (fn [w] (fn [e] {:tag "fix" :word w
                                  :status (keyword (.-value (.-target e)))}))]
    [[:h1
        [:a {:href "#" :class "back"}
          {:onclick (constantly {:tag "back-from-lastround"})} "←"]
        "Last round"]
      [:p "Choose action for each word."]
      (map (fn [[w s]] [[:p w] [:select {:onchange (handler w)}
                                (opt "accept" :ac s)
                                (opt "return to the hat" :hat s)
                                (opt "remove from the game" :rj s)]])
        words)]))

(defonce font-context (.getContext (js/document.createElement "canvas") "2d"))
(defn font-size
  ([s f w h] (font-size s f w 0 (quot (* 2 h) 3)))
  ([s f w l r]
    (if (= r (inc l)) l
      (let [m (quot (+ l r) 2)]
        (set! (.-font font-context) (str "bold " m "px " f))
        (if (> (.-width (.measureText font-context s)) (- w 10))
          (font-size s f w l m)
          (font-size s f w m r))))))

(defn app-font [app-id]
  (.getPropertyValue
    (js/window.getComputedStyle (js/document.getElementById app-id) nil)
    "font-family"))

(defn app-width [app-id]
  (let [root (js/document.getElementById app-id)
        styles (js/window.getComputedStyle root nil)
        px (fn [s]
            (if-not (clojure.string/ends-with? s "px") 0
              (js/parseInt (subs s 0 (- (count s) 2)) 0)))]
    (- (.-clientWidth root)
      (px (.getPropertyValue styles "padding-left"))
      (px (.getPropertyValue styles "padding-right")))))

(defmethod view "explanation" [model app-id]
  (let [f (app-font app-id) w (app-width app-id) h 80]
    [[:h1 (str "Explanation " (:step model))]
      [:div {:class "progress"}
        {:style (str "width: " (/ (* (:ticks (:round model)) w)
                                (:explanation-tl model)) "px")}]
      (map
        (fn [x]
          [[:button {:class "word" :onclick (constantly {:tag "ac" :word x})}
              {:style (str "height: " h "px; font: " (font-size x f w h) "px " f)}
              x]])
        (:hands (:round model)))
      [:button {:onclick (constantly {:tag "giveup"})} "Give up"]]))

(defmethod view "guess" [model app-id]
  (let [f (app-font app-id) w (app-width app-id) h 80]
    [[:h1 (str "Guess " (:step model))]
      [:div {:class "progress"}
        {:style (str "width: " (/ (* (:ticks (:round model)) w)
                                (:guess-tl model)) "px")}]
      (map
        (fn [x]
          [[:button {:class "word" :onclick (constantly {:tag "ac" :word x})}
              {:style (str "height: " h "px; font: " (font-size x f w h) "px " f)}
              x]])
        (:hands (:round model)))]))

(defmethod view "confirm-gameover" [model]
  [[:h1 "Confirmation"]
  [:p "Do you really want to end the game and view the results?"]
  [:button {:onclick (constantly {:tag "complete-gameover"})} "Yes"]
  [:button {:onclick (constantly {:tag "cancel-gameover"})} "No"]])

(defmethod view "confirm-reset" [model]
  [[:h1 "Confirmation"]
  [:p "Do you really want to reset the game?"]
  [:button {:onclick (constantly {:tag "complete-reset"})} "Yes"]
  [:button {:onclick (constantly {:tag "cancel-reset"})} "No"]])

(defn personal-standings [history]
  (let [a> (map (fn [[k v]] [((:pair v) 0) (count (:ac v))]) history)
        a< (map (fn [[k v]] [((:pair v) 1) (count (:ac v))]) history)
        acc (fn [coll] (into {} (map (fn [[k v]] [k (reduce + (map second v))])
                                  (group-by first coll))))
        d> (acc a>) d< (acc a<) d (merge-with + d> d<)]
    (mapv (fn [[_ x]] [x (d> x 0) (d< x 0) (d x)])
      (sort (map (fn [[k v]] [(- v) k]) d)))))

(defn team-standings [history]
  (let [a (map (fn [[k v]] [(:pair v) (count (:ac v))]) history)
        d (into {} (map (fn [[k v]] [k (reduce + (map second v))])
                      (group-by first a)))
        p (map (fn [[k v]] [k (reduce + (map second v))])
                      (group-by (comp vec sort first) a))]
    (mapv (fn [[k v]] [v (d v 0) (d (reverse v) 0) (- k)])
      (sort (map (fn [[k v]] [(- v) k]) p)))))

(defmethod view "results" [model]
  (let [p (sort (set (mapcat (fn [[k v]] (:pair v)) (:history model))))
        a1 (mapcat (fn [[k v]] [[((:pair v) 0) (count (:ac v))]
                                [((:pair v) 1) (count (:ac v))]])
              (:history model))
        s1 (map (fn [[k v]] [(reduce + (map second v)) k]) (group-by first a1))
        a2 (map (fn [[k v]] [(:pair v) (count (:ac v))]) (:history model))
        s2 (map (fn [[k v]] [(reduce + (map second v)) k])
            (group-by (comp vec sort first) a2))]
    [[:h1 "Standings"]
      (when (:personal? model)
        [[:h2 "Personal"]
          [:table
            (map
              (fn [[k a b c]] [:tr [:td {:class "s7s-n"} k] [:td a] [:td b]
                                [:td {:class "s7s-t"} c]])
              (personal-standings (:history model)))]])
      [:h2 "Pairs"]
        [:table
          (map
            (fn [[k a b c]]
              [:tr [:td {:class "s7s-n"} (str (first k) " / " (second k))]
                [:td a] [:td b] [:td c]])
            (team-standings (:history model)))]
    [:button {:class "s7s-b" :onclick (constantly {:tag "newgame"})} "New game"]]))

(defn normalize [x]
  (cond
    (nil? x)
      []
    (and (coll? x) (keyword? (first x)))
      (let [{m+ true m- false} (group-by map? (remove nil? (next x)))
            attrs (merge {} (apply merge m+))
            children (vec (mapcat normalize m-))]
      [[(first x) attrs children]])
    (coll? x)
      (vec (mapcat normalize x))
    :else
      [[x]]))

(defn create-element [[tag attrs children] enqueue]
  (if (keyword? tag)
    (let [element (js/document.createElement (name tag))]
      (doseq [[k v] attrs]
        (if (fn? v)
          (aset element (name k)
            (fn [e]
              (let [a (v e)]
                (if (map? a) (enqueue a) (doseq [x a] (enqueue x))))))
          (.setAttribute element (name k) v)))
      (doseq [c children] (.appendChild element (create-element c)))
      element)
    (js/document.createTextNode tag)))

(defn fix-attrs [element past future enqueue]
  (doseq [x (apply disj (set (keys past)) (keys future))]
    (.removeAttribute element (name x)))
  (doseq [[k v] future]
    (if (not= v (past k))
      (if (fn? v)
        (aset element (name k)
          (fn [e]
            (let [a (v e)]
              (if (map? a) (enqueue a) (doseq [x a] (enqueue x))))))
        (.setAttribute element (name k) v)))))

(defn zip [& colls]
  (let [f (fn [coll] (concat coll (repeat nil)))
        g? (partial not-every? nil?)]
    (take-while g? (apply map vector (map f colls)))))
  
(defn update-dom [element vdom- vdom+ enqueue]
  (let [child-elements (vec (array-seq (.-childNodes element)))]
    (doseq [[x y c] (zip vdom- vdom+ child-elements)]
      (cond
        (= x y) nil
        (nil? x) (.appendChild element (create-element y enqueue))
        (nil? y) (.removeChild element c)
        (not= (first x) (first y))
          (.replaceChild element (create-element y enqueue) c)
        :else
          (when (> (count x) 1)
            (do
              (fix-attrs c (x 1) (y 1) enqueue)
              (update-dom c (x 2) (y 2) enqueue)))))))

(defn render [app-id old-model new-model enqueue]
  (let [a (if (nil? old-model) [] (normalize (view old-model app-id)))
        b (if (nil? new-model) [] (normalize (view new-model app-id)))]
    (update-dom (js/document.getElementById app-id) a b enqueue)))

(defn handle [model msg]
  (let [x (move model msg)]
    (if (vector? x)
      (let [[m c] x]
        (case (:cmd c)
          "beep"
          (do (beep) m)

          "choose"
          (move m {:tag "choice" :coll (:coll c)
                    :selection (vec (take (:k c) (shuffle (:coll c))))})))
      x)))

(defonce storage js/localStorage)
(defn save-clj [key data]
  (.setItem storage key (str data)))
(defn load-clj [key]
  (when-let [s (.getItem storage key)]
    (cljs.reader/read-string s)))

(defn run-app
  "Runs application in the given element."
  [app-id]
  (let [state (atom {:on true :model (if-let [x (load-clj "hat")] new-game new-game)})
        queue (atom '())]
   (letfn [(enqueue [msg] (swap! queue conj msg))
            (dequeue []
              (loop [x @queue]
                (if (compare-and-set! queue x '())
                  (reverse x)
                  (recur @queue))))
            (stop [timer-id]
              (fn []
                (js/clearInterval timer-id)
                (swap! state assoc :on false)))
            (main []
              (let [m (reduce handle (:model @state) (dequeue))]
                (when (not= m (:model @state))
                  (render app-id (:model @state) m enqueue)
                  (swap! state assoc :model m)
                  (save-clj "hat" m)))
              (when (:on @state)
                (js/setTimeout main 100)))]
    (render app-id nil (:model @state) enqueue)
    (main)
    [(stop (js/setInterval (fn [] (enqueue {:tag "tick"})) 1000))
      state queue enqueue dequeue])))

(set! (.-onload js/window) (fn [_] (run-app "root")))
