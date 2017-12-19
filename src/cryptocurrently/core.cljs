(ns cryptocurrently.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as r]
            [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! close! chan]]
            [goog.string :as gstring]
            [goog.string.format]))

(defonce btc-price (r/atom 0))
(defonce ltc-price (r/atom 0))
(defonce eth-price (r/atom 0))

;; -------------------------
;; Websockets

(defn connect [ch url]
  (go 
    (let [{:keys [ws-channel error]} (<! (ws-ch url {:format :json}))]
      (if-not error
        (reset! ch ws-channel)
        (js/console.log error)))))

(defn subscribe [ch product-ids events]
  (go
    (>! ch {:type "subscribe"
            :product_ids product-ids
            :channels events}))) 

(defmulti event-handler (fn [message log-enabled] [(message "type") (message "product_id")]))
(defmethod event-handler ["ticker" "BTC-USD"] [message log-enabled]
  (reset! btc-price (message "price"))
  (when (true? log-enabled) (prn (str "Bitcoin: $" (message "price")))))
(defmethod event-handler ["ticker" "LTC-USD"] [message log-enabled]
  (reset! ltc-price (message "price"))
  (when (true? log-enabled) (prn (str "Litecoin: $" (message "price")))))
(defmethod event-handler ["ticker" "ETH-USD"] [message log-enabled]
  (reset! eth-price (message "price"))
  (when (true? log-enabled) (prn (str "Etherium: $" (message "price")))))
(defmethod event-handler :default [message log-enabled]
  (when (true? log-enabled) (prn "Unhandled message: " message)))

(defn handle-events [ch]
  (go-loop []
    (when-let [{:keys [message]} (<! ch)]
      (event-handler message true)
      (recur))))

(def ws-feed-url "wss://ws-feed.gdax.com")
(defonce channel (atom (chan)))
(def currencies ["BTC-USD" "ETH-USD" "LTC-USD"])
(def events ["ticker"])

(add-watch channel nil
  (fn [k r os ch]
    (if-not (nil? ch)
      (do
        (handle-events ch)
        (subscribe ch currencies events)))))

(connect channel ws-feed-url)

;; -------------------------
;; Views

(defn currency-row [name price]
  [:div
   [:h3 name]
   [:h4 (str "$" (gstring/format "%.2f" @price))]])

(defn home-page []
  [:div 
    [:h2 "CryptoCurrently"]
    [:div
      [currency-row "Bitcoin" btc-price]
      [currency-row "Litecoin" ltc-price]
      [currency-row "Etherium" eth-price]]])

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root))
