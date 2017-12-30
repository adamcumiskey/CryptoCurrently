(ns cryptocurrently.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as r]
            [stylefy.core :as stylefy]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! close! chan]]
            [goog.string :as gstring]
            [goog.string.format]))


;; -------------------------
;; Constants

(def ws-feed-url "wss://ws-feed.gdax.com")
(def products ["BTC-USD" "ETH-USD" "LTC-USD"])
(def events ["ticker" "level2"])
(def visible-orders 10)


;; -------------------------
;; State

(defonce channel (atom (chan))) 

(defonce btc-price (r/atom 0))
(defonce btc-orders (r/atom (ring-buffer visible-orders)))

(defonce ltc-price (r/atom 0))
(defonce ltc-orders (r/atom (ring-buffer visible-orders)))

(defonce eth-price (r/atom 0))
(defonce eth-orders (r/atom (ring-buffer visible-orders)))


;; ------------------------
;; Event Handlers

;;;; Ticker

(defmulti ticker-event-handler (fn [message log-enabled] (message "product_id")))

(defmethod ticker-event-handler "BTC-USD" [message log-enabled]
  (reset! btc-price (message "price"))
  (when (true? log-enabled) (prn (str "Bitcoin: $" (message "price")))))

(defmethod ticker-event-handler "LTC-USD" [message log-enabled]
  (reset! ltc-price (message "price"))
  (when (true? log-enabled) (prn (str "Litecoin: $" (message "price")))))

(defmethod ticker-event-handler "ETH-USD" [message log-enabled]
  (reset! eth-price (message "price"))
  (when (true? log-enabled) (prn (str "Ethereum: $" (message "price")))))

;;;; L2Update (buy/sell orders)

(defn format-order [order]
  {:type "order"
   :time (order "time")
   :product_id (order "product_id")
   :order_type (first (first (order "changes")))
   :order_price (second (first (order "changes")))
   :order_amount (last (first (order "changes")))})

(defmulti order-event-handler (fn [message log-enabled] (message "product_id")))

(defmethod order-event-handler "BTC-USD" [message log-enabled]
  (swap! btc-orders conj (format-order message))
  (when (true? log-enabled) (prn "BTC:" (format-order message))))
(defmethod order-event-handler "LTC-USD" [message log-enabled]
  (swap! ltc-orders conj (format-order message))
  (when (true? log-enabled) (prn "LTC:" (format-order message))))
(defmethod order-event-handler "ETH-USD" [message log-enabled]
  (swap! eth-orders conj (format-order message))
  (when (true? log-enabled) (prn "ETH:" (format-order message))))

(defmethod order-event-handler :default [message log-enabled]
  (when (true? log-enabled) (prn (str "Unhandled Order: " (format-order message)))))

;;;; Base

(defmulti event-handler (fn [message log-enabled] (message "type")))

(defmethod event-handler "ticker" [message log-enabled]
  (ticker-event-handler message log-enabled))

(defmethod event-handler "l2update" [message log-enabled]
  (order-event-handler message log-enabled))

(defmethod event-handler :default [message log-enabled]
  (when (true? log-enabled) (prn (str "Unhandled message: " message))))


;; -------------------------
;; Websockets

(defn connect [ch url]
  "Establish connection to websocket and set value of ch"
  (go 
    (let [{:keys [ws-channel error]} (<! (ws-ch url {:format :json}))]
      (if-not error
        (reset! ch ws-channel)
        (js/console.log error)))))

(defn subscribe [ch product-ids events]
  "Subscribe to events for the given product-ids.
   Required to be sent within 5s of connecting."
  (go
    (>! ch {:type "subscribe"
            :product_ids product-ids
            :channels events}))) 

(defn add-event-handler [ch]
  "Dispatch messages from ch to the event-handler"
  (go-loop []
    (when-let [{:keys [message]} (<! ch)]
      (event-handler message false)
      (recur))))

(defn start-updating [ch products events]
  "Connect to the websocket feed.
   On success, start the event-hander and subscribe
   to a feed."
  (add-watch ch nil
    (fn [k r os ch]
      (if-not (nil? ch)
        (do
          (add-event-handler ch)
          (subscribe ch products events)))))
  (connect ch ws-feed-url))


;; -------------------------
;; Styles

(def phone-width "600px")
(def tablet-width "774px")

(def clearfix {::stylefy/mode {:after {:clear "both"
                                       :content ""
                                       :display "block"
                                       :height 0
                                       :line-height 0
                                       :visiblility "hidden"}}})

(def dark-style {:background-color "#333"
                 :color "#FFF"})
(def light-style {:background-color "#FFF"
                  :color "#333"})

(def header-style (merge dark-style {:margin "0px"
                                     :padding "20px"
                                     :text-align "center"}))

(def main-content-style (merge light-style clearfix {}))

(def currency-column-style {::stylefy/media {{:max-width tablet-width} {:width "100%"}}
                            :float "left"
                            :width "33%"
                            :padding "15px"})

(def currency-header-style {:padding "20px"
                            :height "20%"})

(def currency-order-table-style {:border "1px solid black"
                                 :border-collapse "collapse"
                                 :width "100%"
                                 :height "80%"
                                 ::stylefy/sub-styles {:td {:background-color "#FFF"
                                                            :border "1px solid black"
                                                            :width "33%"
                                                            :padding "8px"}
                                                       :th {:padding "8px"}}})


;; -------------------------
;; Views

(defn header []
  [:div (stylefy/use-style header-style)
     [:h1 "CryptoCurrently"]])

(defn currency-header [currency price]
  [:div (stylefy/use-style currency-header-style)
   [:h1 (stylefy/use-style {:text-align "center"}) 
    currency]
   [:h2 (stylefy/use-style {:text-align "center"}) 
    (str "$" (gstring/format "%.2f" @price))]])   

(defn currency-order-table-header []
  [:tr (stylefy/use-style dark-style)
   [:th (stylefy/use-sub-style currency-order-table-style :th) "Order Type"]
   [:th (stylefy/use-sub-style currency-order-table-style :th) "Price"]
   [:th (stylefy/use-sub-style currency-order-table-style :th) "Amount"]])

(defn currency-order-table-row [order]
  (let [{:keys [order_type order_price order_amount]} order]
    [:tr
     [:td (stylefy/use-sub-style currency-order-table-style :td)
      order_type]
     [:td (stylefy/use-sub-style currency-order-table-style :td)
      (str "$" (gstring/format "%.2f" order_price))]
     [:td (stylefy/use-sub-style currency-order-table-style :td)
      (gstring/format "%.5f" order_amount)]]))   

(defn currency-order-table [orders]
  [:table
   (stylefy/use-style currency-order-table-style)
   [currency-order-table-header]
   (for [order (reverse @orders)]
     ^{:key order} [currency-order-table-row order])])

(defn currency-column [currency price orders]
  [:div (stylefy/use-style currency-column-style)
   [currency-header currency price]
   [currency-order-table orders]])

(defn main-content []
  [:div (stylefy/use-style main-content-style)
   [currency-column "BTC" btc-price btc-orders]
   [currency-column "LTC" ltc-price ltc-orders]
   [currency-column "ETC" eth-price eth-orders]])

(defn page []
  [:div
    [header]
    [main-content]])


;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [page] (.getElementById js/document "app")))

(defn init! []
  (stylefy/init)
  (mount-root)
  (start-updating channel products events))
