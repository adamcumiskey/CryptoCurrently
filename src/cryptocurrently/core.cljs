(ns cryptocurrently.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as r]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! close! chan]]
            [goog.string :as gstring]
            [goog.string.format]))

;; -------------------------
;; Constants

(def ws-feed-url "wss://ws-feed.gdax.com")
(def products ["BTC-USD" "ETH-USD" "LTC-USD"])
(def product-names ["Bitcoin" "Ethereum" "Litecoin"])
(def events ["ticker" "level2"])
(def visible-orders 10)


;; -------------------------
;; State

(defonce ws-channel (atom (chan)))

(defn coin [id name]
  {:product-id id
   :name name
   :price 0
   :orders (ring-buffer visible-orders)})

(def store (r/atom (reduce 
             (fn [col c] 
               (assoc col (:product-id c) c))
             {} 
             (map #(apply coin %) (map list products product-names)))))

(defn set-price [store product-id price]
  (let [coins @store
        coin (coins product-id)]
    (swap! store (assoc-in coins [product-id :price] price))))

(defn add-order [store product-id order]
  (let [coins @store
        coin (coins product-id)
        orders (coin :orders)]
    (swap! store (assoc-in coins [product-id :orders] (conj orders order)))))



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


;; ------------------------
;; Event Handlers


(defn format-order [order]
  {:type "order"
   :time (order "time")
   :product_id (order "product_id")
   :order_type (first (first (order "changes")))
   :order_price (second (first (order "changes")))
   :order_amount (last (first (order "changes")))})


(defmulti event-handler (fn [message log-enabled] (message "type")))

(defmethod event-handler "ticker" [message log-enabled]
  (set-price store (message "product_id") (message "price")))

(defmethod event-handler :default [message log-enabled]
  (when (true? log-enabled) (prn (str "Unhandled message: " message))))

(defn add-event-handler [ch]
  "Dispatch messages from ch to the event-handler"
  (go-loop []
    (when-let [{:keys [message]} (<! ch)]
      (event-handler message false)
      (recur))))


;; -------------------------
;; Views

(defn order-element [order]
  [:div
   {:style {:display :flex
            :flex-direction :row
            :justify-content :space-around}}
   [:p (order :order_type)]
   [:p (str "$" (gstring/format "%.2f" (order :order_price)))]
   [:p (gstring/format "%.5f" (order :order_amount))]])

(defn orders-element [orders]
  "Order stream for a currency"
  [:div
   {:class "col-4"}
   (for [item @orders]
     ^{:key item} [order-element item])])

(defn currency-element [coin]
  "Div for a single currency"
  [:div
   {:class "currency-element col-4"}
   [:h2 (:name coin)]
   [:h3
    (str "$" (gstring/format "%.2f" (:price coin)))]])

(defn nav-bar []
  [:div
     [:h1 "CryptoCurrently"]])

(defn home-page []
  [:div 
    [nav-bar]
    [:div
      {:class "content"}
      (for [coin @store]
        ^{key coin} [currency-element coin] (prn coin))]])


;; -------------------------
;; Initialize app

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

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root)
  (start-updating ws-channel products events))
