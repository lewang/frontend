(ns frontend.components.build-config
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [raise!]]
            [frontend.components.common :as common]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true])
    (:require-macros [frontend.utils :refer [html]]))

(defn- maybe-add-start-end
  "Workaround around missing start/end data from backend"
  [error]
  (if (:start error)
    error
    (assoc error
           :start {:line 0 :index 0 :column 0}
           :end {:line 0 :index 0 :column 0})))

(defn diagnostics [config owner]
  (reify
    om/IInitState
    (init-state [_]
      ;; TODO convert CI.inner.Diagnostics to clojurescript
      {:configDiagnostics (js/CI.inner.Diagnostics. (:string config) (->> config :errors (map maybe-add-start-end) clj->js))})
    om/IRenderState
    (render-state [_ {:keys [configDiagnostics]}]
      (html
       [:section
        [:table.code
         [:thead [:tr [:th] [:th]]]
         [:tbody
          (for [line (aget configDiagnostics "lines")]
            (list [:tr {:class (when (aget line "has_errors") "error")}
                   [:td.line-number (aget line "line")]
                   [:td.line
                    (for [piece (aget line "pieces")]
                      (list
                       (when (aget piece "data")
                         [:span {:class (when (aget piece "error") "error")}
                          (aget piece "data")])
                       (when (aget piece "error_flag")
                         [:span [:a.error-flag {:on-click #(do ((aget piece "select"))
                                                               (om/refresh! owner))
                                                :class (when ((aget piece "get_selected")) "selected")}
                                 (inc (aget piece "number"))]])))]]
                  (for [error (aget line "errors")]
                    [:tr.error-message {:class (when ((aget error "get_selected")) "opened")}
                     [:td.line-number]
                     [:td.error-message
                      (when (aget error "path")
                        [:span.path (aget error "path")])
                      (aget error "message") "."
                      [:div.next-button
                       [:a {:on-click #(do ((aget error "select_next"))
                                           (om/refresh! owner))}
                        [:i.fa.fa-angle-right]]]]])))]]]))))

(defn config-errors [build owner]
  (reify
    om/IRender
    (render [_]
      (let [config (:circle_yml build)]
        (html
         [:div.config-diagnostics.heroic
          (when-not (:lethal config)
            [:button.dismiss {:on-click #(raise! owner [:dismiss-config-errors])}
             "Dismiss "
             [:i.fa.fa-times-circle]])
          [:header
           [:div.head-left
            (if (:lethal config)
              (common/icon {:name "fail" :type "status"})
              [:i.error.fa.fa-exclamation-triangle])]
           [:div.head-right
            [:h2 "Dang."]
            [:p
             "We spotted some issues with your " [:code "circle.yml"] "."

             (when-not (:lethal config)
               (if (:failed build)
                 " These may be causing your build to fail! We recommend that you fix them as soon as possible."
                 " These may lead to unexpected behavior and may cause your build to fail soon. We recommend that you fix them as soon as possible."))]
            [:p
             "You may want to look at "
             [:a {:href "/docs/configuration"} "our docs"]
             " or " (common/contact-us-inner owner) " if you're having trouble."]]]
          (om/build diagnostics config)])))))
