(ns frontend.components.admin
  (:require [ankha.core :as ankha]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [raise!]]
            [frontend.components.about :as about]
            [frontend.components.common :as common]
            [frontend.components.shared :as shared]
            [frontend.datetime :as datetime]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn build-state [app owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Build State")
    om/IRender
    (render [_]
      (let [build-state (get-in app state/build-state-path)]
        (html
         [:section {:style {:padding-left "10px"}}
          [:a {:href "/api/v1/admin/build-state" :target "_blank"} "View raw"]
          " / "
          [:a {:on-click #(raise! owner [:refresh-admin-build-state-clicked])} "Refresh"]
          (if-not build-state
            [:div.loading-spinner common/spinner]
            [:code (om/build ankha/inspector build-state)])])))))

(defn switch [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.container-fluid
        [:div.row-fluid
         [:div.span9
          [:p "Switch user"]
          [:form.form-inline {:method "post", :action "/admin/switch-user"}
           [:input.input-medium {:name "login", :type "text"}]
           [:input {:value (utils/csrf-token)
                    :name "CSRFToken",
                    :type "hidden"}]
           [:button.btn.btn-primary {:value "Switch user", :type "submit"}
            "Switch user"]]]]]))))

(defn overview [app owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Build State")
    om/IRender
    (render [_]
      (html
        [:section {:style {:padding-left "10px"}}
         [:h1 "CircleCI Version Info"]
         [:p
          "You are running "
          [:b
           "CircleCI "
           (if-let [enterprise-version (get-in app [:render-context :enterprise_version])]
             (list
               "Enterprise "
               enterprise-version)
             (list
               "in "
               (:environment app)))]
          "."]]))))

(defn fleet-state [app owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Build State")
    om/IRender
    (render [_]
      (let [fleet-state (sort-by :instance_id (get-in app state/fleet-state-path))]
        (html
         [:section {:style {:padding-left "10px"}}
          [:header
           [:a {:href "/api/v1/admin/build-state-summary" :target "_blank"} "View raw"]
           " / "
           [:a {:on-click #(raise! owner [:refresh-admin-fleet-state-clicked])} "Refresh"]]
          (if-not fleet-state
            [:div.loading-spinner common/spinner]
            ;; FIXME: This table shouldn't really be .recent-builds-table; it's
            ;; a hack to steal a bit of styling from the builds table until we
            ;; properly address the styling for this table and admin tools in
            ;; general.
            [:table.recent-builds-table
             [:thead
              [:tr
               [:th "Instance ID"]
               [:th "Instance Type"]
               [:th "Boot Time"]
               [:th "Busy Containers"]
               [:th "State"]]]
             [:tbody
              (if (seq fleet-state)
                (for [instance fleet-state]
                  [:tr
                   [:td (:instance_id instance)]
                   [:td (:ec2_instance_type instance)]
                   [:td (datetime/long-datetime (:boot_time instance))]
                   [:td (:busy instance) " / " (:total instance)]
                   [:td (:state instance)]])
                [:tr
                 [:td "No available masters"]])]])])))))


(defn license [app owner]
  (reify
    om/IDisplayName (display-name [_] "License Info")
    om/IRender
    (render [_]
      (html
        [:section {:style {:padding-left "10px"}}
         [:h1 "License Info"]
         (let [license (get-in app state/license-path)]
           (if-not license
             [:div.loading-spinner common/spinner]
             (list
              [:p "License Type: " [:b (:type license)]]
              [:p "License Status: Term (" [:b (:expiry_status license)] "), Seats (" [:b (:seat_status license)] ")"]
              [:p "Expiry date: " [:b (datetime/medium-date (:expiry_date license))]])))]))))

(defn user [{:keys [user action action-name]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:li
         (:login user)
         " "
         (when action
           [:button.btn.btn-xs.btn-primary
            {:on-click #(raise! owner [action (select-keys user [:login])])}
            action-name])]))))

(defn users [app owner]
  (reify
    om/IDisplayName (display-name [_] "User Admin")

    om/IRender
    (render [_]
      (let [all-users (:all-users app)
            active-users (filter #(and (not= 0 (:login-count %))
                                       (not (:suspended %)))
                                 all-users)
            suspended-users (filter :suspended all-users)
            admin-write-scope? (#{"all" "write-settings"}
                                  (get-in app [:current-user :admin]))]
        (html
          [:section {:style {:padding-left "10px"}}
           [:h1 "Users"]

          [:p "Suspended users are prevented from logging in and do not count towards the number your license allows."]

          [:h2 "active"]
          [:ul (om/build-all user (mapv #(merge {:user %}
                                                (when admin-write-scope?
                                                  {:action :suspend-user
                                                   :action-name "suspend"}))
                                        active-users))]

         [:h2 "suspended"]
           [:ul (om/build-all user (mapv #(merge {:user %}
                                                 (when admin-write-scope?
                                                   {:action :unsuspend-user
                                                    :action-name "reactivate"}))
                                         suspended-users))]])))))

(defn admin-settings [app owner]
  (reify
    om/IRender
    (render [_]
      (let [subpage (:admin-settings-subpage app)]
        (html
         [:div#admin-settings
            [:div.admin-settings-inner
             [:div#subpage
              (case subpage
                :fleet-state (om/build fleet-state app)
                :license (om/build license app)
                :users (om/build users app)
                (om/build overview app))]]])))))
