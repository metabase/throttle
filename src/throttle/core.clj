(ns throttle.core
  (:require [clojure.math.numeric-tower :as math])
  (:import (clojure.lang Atom Keyword)))

;;; # PUBLIC INTERFACE

(declare calculate-delay remove-old-attempts)

(defrecord Throttler [;; Name of the API field/value being checked. Used to generate appropriate API error messages, so
                      ;; they'll be displayed on the right part of the screen
                      ^Keyword exception-field-key
                      ;; [Internal] List of attempt entries. These are pairs of [key timestamp (ms)],
                      ;; e.g. ["cam@metabase.com" 1438045261132]
                      ^Atom    attempts
                      ;; Amount of time to keep an entry in `attempts` before dropping it.
                      ^Integer attempt-ttl-ms
                      ;; Number of attempts allowed with a given key before throttling is applied.
                      ^Integer attempts-threshold
                      ;; Once throttling is in effect, initial delay before allowing another attempt. This grows
                      ;; according to `delay-exponent`.
                      ^Integer initial-delay-ms
                      ;; For each subsequent failure past `attempts-threshold`, increase the delay to `initial-delay-ms
                      ;; * (num-attempts-over-theshold ^ delay-exponent)`. e.g. if `initial-delay-ms` is 15 and
                      ;; `delay-exponent` is 2, the first attempt past `attempts-threshold` will require the user to
                      ;; wait 15 seconds (15 * 1^2), the next attempt after that 60 seconds (15 * 2^2), then 135, and so
                      ;; on.
                      ^Integer delay-exponent])

;; These are made private because you should use `make-throttler` instead.
(alter-meta! #'->Throttler assoc :private true)
(alter-meta! #'map->Throttler assoc :private true)

(def ^:private ^:const throttler-defaults
  {:initial-delay-ms   (* 15 1000)
   :attempts-threshold 10
   :delay-exponent     1.5
   :attempt-ttl-ms     (* 1000 60 60)})

(defn make-throttler
  "Create a new `Throttler`.

     (require '[metabase.api.common.throttle :as throttle])
     (def email-throttler (throttle/make-throttler :email, :attempts-threshold 10))"
  [exception-field-key & {:as kwargs}]
  (map->Throttler (merge throttler-defaults kwargs {:attempts            (atom '())
                                                    :exception-field-key exception-field-key})))

(defn check
  "Throttle an API call based on values of `keyy`. Each call to this function will record `keyy` to `throttler`'s
  internal list; if the number of entires containing `keyy` exceed `throttler`'s thresholds, throw an exception.

     (defendpoint POST [:as {{:keys [email]} :body}]
       (throttle/check email-throttler email)
       ...)"
  ;; Technically, `keyy` can be nil so you can record *all* attempts.
  [^Throttler {:keys [attempts exception-field-key], :as throttler} keyy]
  {:pre [(= (type throttler) Throttler)]}
  (remove-old-attempts throttler)
  (when-let [delay-ms (calculate-delay throttler keyy)]
    (let [message (format "Too many attempts! You must wait %d seconds before trying again."
                          (int (math/round (/ delay-ms 1000))))]
      (throw (ex-info message {:status-code 400
                               :errors      {exception-field-key message}}))))
  (swap! attempts conj [keyy (System/currentTimeMillis)]))

(defn- remaining-attempts
  [^Throttler {:keys [attempts attempts-threshold]} keyy]
  (let [[[_ most-recent-attempt-ms], :as keyy-attempts] (filter (fn [[k _]] (= k keyy)) @attempts)]
    (if most-recent-attempt-ms
      (let [num-recent-attempts (count keyy-attempts)]
        (- attempts-threshold num-recent-attempts))
      attempts-threshold)))

(defn- make-throttling-ex
  [{:keys [exception-field-key] :as throttler} keyy]
  (let [delay-ms (calculate-delay throttler keyy)
        message  (if delay-ms
                   (format "Too many attempts! You must wait %d seconds before trying again."
                           (int (math/round (/ delay-ms 1000))))
                   "Too many attempts! Please try again later.")]
    (ex-info message {:errors {exception-field-key message}})))

(defn- add-attempt!
  [^Throttler {:keys [attempts]} keyy]
  (swap! attempts conj [keyy (System/currentTimeMillis)]))

(defn do-with-throttling
  [^Throttler throttler keyy f]
  (remove-old-attempts throttler)
  (try
    (when (<= (remaining-attempts throttler keyy) 0)
      (throw (make-throttling-ex throttler keyy)))
    (f)
    (catch Throwable e
      (add-attempt! throttler keyy)
      (throw e))))

(defmacro with-throttling
  "Do `body` if failed attempts for `keyy` on `throttler` has not been exceeded.
  If `body` throws an exception, a failed attempt is counted and the exception is re-thrown. If the failed attempts
  threshold is exceeded, an exception is thrown and `body` is not executed. Attempts made while the threshold is
  exceeded are counted as additional failed attempts."
  {:style/indent 2}
  [[throttler key & more] & body]
  (if (seq more)
    `(with-throttling [~throttler ~key]
       (with-throttling [~@more]
         ~@body))
    `(do-with-throttling ~throttler ~key (fn [] ~@body))))

;;; # INTERNAL IMPLEMENTATION

(defn- remove-old-attempts
  "Remove `throttler` entires past the TTL."
  [^Throttler {:keys [attempts attempt-ttl-ms]}]
  (let [old-attempt-cutoff (- (System/currentTimeMillis) attempt-ttl-ms)
        non-old-attempt?   (fn [[_ timestamp]]
                             (> timestamp old-attempt-cutoff))]
    (reset! attempts (take-while non-old-attempt? @attempts))))

(defn- calculate-delay
  "Calculate the delay in milliseconds, if any, that should be applied to a given `throttler`/`keyy` combination."
  ([^Throttler throttler keyy]
   (calculate-delay throttler keyy (System/currentTimeMillis)))

  ([^Throttler {:keys [attempts initial-delay-ms attempts-threshold delay-exponent]} keyy current-time-ms]
   (let [[[_ most-recent-attempt-ms], :as keyy-attempts] (filter (fn [[k _]] (= k keyy)) @attempts)]
     (when most-recent-attempt-ms
       (let [num-recent-attempts         (count keyy-attempts)
             ;; Add one to the sum to account for the current attempt:
             num-attempts-over-threshold (- (inc num-recent-attempts) attempts-threshold)]
         (when (> num-attempts-over-threshold 0)
           (let [delay-ms              (* (math/expt num-attempts-over-threshold delay-exponent)
                                          initial-delay-ms)
                 next-login-allowed-at (+ most-recent-attempt-ms delay-ms)
                 ms-till-next-login    (- next-login-allowed-at current-time-ms)]
             (when (> ms-till-next-login 0)
               ms-till-next-login))))))))
