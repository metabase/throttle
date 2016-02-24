(defproject metabase/throttle "1.0.0"
  :description "Simple tools for throttling API endpoints and other code."
  :url "https://github.com/metabase/throttle"
  :license {:name "Lesser GPL"
            :url "https://www.gnu.org/licenses/lgpl.txt"}
  :min-lein-version "2.5.0"
  :dependencies [[org.clojure/math.numeric-tower "0.0.4"
                  :exclusions [org.clojure/clojure]]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [expectations "2.1.4"]]
                   :plugins [[lein-expectations "0.0.8"]
                             [jonase/eastwood "0.2.3"]
                             [lein-bikeshed "0.3.0"]]
                   :eastwood {:add-linters [:unused-private-vars]}
                   :aliases {"bikeshed" ["with-profile" "+bikeshed" "bikeshed" "--max-line-length" "160"]
                             "test" ["expectations"]}}})
