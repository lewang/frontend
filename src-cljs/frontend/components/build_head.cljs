(ns frontend.components.build-head
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as string]
            [frontend.async :refer [raise!]]
            [frontend.datetime :as datetime]
            [frontend.models.build :as build-model]
            [frontend.models.plan :as plan-model]
            [frontend.models.feature :as feature]
            [frontend.models.test :as test-model]
            [frontend.components.builds-table :as builds-table]
            [frontend.components.build :as build]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.config :refer [intercom-enabled? github-endpoint env enterprise?]]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.timer :as timer]
            [frontend.utils :as utils :include-macros true :refer [mlog merror]]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.visualization.build :as viz-build]
            [goog.string :as gstring]
            [goog.string.format]
            [inflections.core :refer (pluralize)]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html defrender inspect]]))

;; This is awful, can't we just pass build-head the whole app state?
;; splitting it up this way means special purpose paths to find stuff
;; in it depending on what sub-state with special keys we have, right?
(defn has-scope [scope data]
  (scope (:scopes data)))

(defn show-additional-containers-offer? [plan build]
  (when (and plan build (not (enterprise?)))
    (let [usage-queued-ms (build-model/usage-queued-time build)
          run-queued-ms (build-model/run-queued-time build)]
      ;; more than 10 seconds waiting for other builds, and
      ;; less than 10 seconds waiting for additional containers (our fault)
      (< run-queued-ms 10000 usage-queued-ms))))

(defn additional-containers-offer [plan build]
  [:p#additional_containers_offer
   "Too much waiting? You can " [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                                            :subpage "containers"})}
                                 "add more containers"]
   " and finish even faster."])

(defn new-additional-containers-offer [plan build]
  (let [run-phrase (if (build-model/finished? build)
                     "ran"
                     "is running")]
    [:div.additional-containers-offer
     [:p
      "This build "
      run-phrase
      " under "
      (:org_name plan)
      "'s plan which provides "
      (plan-model/usable-containers plan)
      " containers, plus 3 additional containers available for free and open source projects. "
      [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                  :subpage "containers"})}
       "Add Containers"]
      " to run more builds concurrently."]]))

(defn build-queue [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [{:keys [build]} data
            build-id (build-model/id build)]
        (raise! owner [:usage-queue-why-showed
                       {:build-id build-id
                        :username (:username @build)
                        :reponame (:reponame @build)
                        :build_num (:build_num @build)}])))

    om/IRender
    (render [_]
      (let [{:keys [build builds]} data
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            plan (:plan data)]
        (html
         (if-not builds
           [:div.loading-spinner common/spinner]
           [:div.build-queue.active
            (when (and (:queued_at build) (not usage-queued?))
              [:p "Circle " (when run-queued? "has") " spent "
               (om/build common/updating-duration {:start (:queued_at build)
                                                   :stop (or (:start_time build) (:stop_time build))})
               " acquiring containers for this build."])
            (when (< 10000 (build-model/run-queued-time build))
              [:p#circle_queued_explanation
               "We're sorry; this is our fault. Typically you should only see this when load spikes overwhelm our auto-scaling; waiting to acquire containers should be brief and infrequent."])

            (when (seq builds)
              (list
               [:p "This build " (if usage-queued? "has been" "was")
                " queued behind the following builds for "
                (om/build common/updating-duration {:start (:usage_queued_at build)
                                                    :stop (or (:queued_at build) (:stop_time build))})
                "."]

               (om/build builds-table/builds-table builds {:opts {:show-actions? true}})))
            (when (show-additional-containers-offer? plan build)
              (if (om/get-shared owner [:ab-tests :new_usage_queued_upsell])
                (new-additional-containers-offer plan build)
                (additional-containers-offer plan build)))]))))))

(defn build-queue-v2 [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [{:keys [build]} data
            build-id (build-model/id build)]
        (raise! owner [:usage-queue-why-showed
                       {:build-id build-id
                        :username (:username @build)
                        :reponame (:reponame @build)
                        :build_num (:build_num @build)}])))

    om/IRender
    (render [_]
      (let [{:keys [build builds]} data
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            plan (:plan data)]
        (html
         (if-not builds
           [:div.loading-spinner common/spinner]
           [:div.build-queue.active
            [:div
             (when (and (:queued_at build) (not usage-queued?))
               (list
                 "Circle " (when run-queued? "has") " spent "
                 [:strong
                  (om/build common/updating-duration {:start (:queued_at build)
                                                      :stop (or (:start_time build) (:stop_time build))})]
                 " acquiring containers for this build."))

             (when (< 10000 (build-model/run-queued-time build))
               [:span#circle_queued_explanation
                " We're sorry; this is our fault. Typically you should only see this when load spikes overwhelm our auto-scaling; waiting to acquire containers should be brief and infrequent."]) 
             (when (seq builds)
               [:span
                " This build " (if usage-queued? "has been" "was")
                " queued behind the following builds for "
                [:strong
                 (om/build common/updating-duration {:start (:usage_queued_at build)
                                                     :stop (or (:queued_at build) (:stop_time build))})]
                (when (show-additional-containers-offer? plan build)
                  (new-additional-containers-offer plan build))])]

              (when (seq builds)
               [:div.queued-builds
                (om/build builds-table/builds-table-v2 builds {:opts {:show-actions? true}})])]))))))

(defn linkify [text]
  (let [url-pattern #"(?im)(\b(https?|ftp)://[-A-Za-z0-9+@#/%?=~_|!:,.;]*[-A-Za-z0-9+@#/%=~_|])"
        pseudo-url-pattern #"(?im)(^|[^/])(www\.[\S]+(\b|$))"]
    (-> text
        ;; TODO: switch to clojure.string/replace when they fix
        ;; http://dev.clojure.org/jira/browse/CLJS-485...
        (.replace (js/RegExp. (.-source url-pattern) "gim")
                  "<a href=\"$1\" target=\"_blank\">$1</a>")
        (.replace (js/RegExp. (.-source pseudo-url-pattern) "gim")
                  "$1<a href=\"http://$2\" target=\"_blank\">$2</a>"))))

(defn maybe-project-linkify [text project-name]
  (if-not project-name
    text
    (let [issue-pattern #"(^|\s)#(\d+)\b"]
      (-> text
          (string/replace issue-pattern
                          (gstring/format "$1<a href='%s/%s/issues/$2' target='_blank'>#$2</a>" (gh-utils/http-endpoint) project-name))))))

(defn commit-line [{:keys [build subject body commit_url commit] :as commit-details} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (when (seq body)
        (utils/tooltip (str "#commit-line-tooltip-hack-" commit)
                       {:placement "bottom"
                        :animation false
                        :viewport "#build-log-container"})))
    om/IRender
    (render [_]
      (html
       [:div
        [:span.commit-message
         {:title body
          :id (str "commit-line-tooltip-hack-" commit)
          :dangerouslySetInnerHTML {:__html (-> subject
                                                (gstring/htmlEscape)
                                                (linkify)
                                                (maybe-project-linkify (vcs-url/project-name (:vcs_url build))))}}]
        [:a.sha-one {:href commit_url
                     :title commit}
         " "
         (subs commit 0 7)
         " "
         [:i.fa.fa-github]]]))))

(defn build-commits [build-data owner]
  (reify
    om/IRender
    (render [_]
      (let [build (:build build-data)
            build-id (build-model/id build)]
        (html
         [:div.build-commits-container
          [:div.build-commits-title
           (when (:compare build)
             [:a {:href (:compare build)}
              "compare "
              [:i.fa.fa-github]
              " "])
           (when (< 3 (count (:all_commit_details build)))
             [:a {:role "button"
                  :on-click #(raise! owner [:show-all-commits-toggled {:build-id build-id}])}
              (str (- (count (:all_commit_details build)) 3) " more ")
              (if (:show-all-commits build-data)
                [:i.fa.fa-caret-up]
                [:i.fa.fa-caret-down])])]
          (when (:subject build)
            [:div.build-commits-list
             (if-not (seq (:all_commit_details build))
               (om/build commit-line {:build build
                                      :subject (:subject build)
                                      :body (:body build)
                                      :commit_url (build-model/github-commit-url build)
                                      :commit (:vcs_revision build)})
               (list
                (om/build-all commit-line (take 3 (map #(assoc % :build build)
                                                       (:all_commit_details build))))
                (when (:show-all-commits build-data)
                  (om/build-all commit-line (drop 3 (map #(assoc % :build build)
                                                         (:all_commit_details build)))))))])])))))

(defn ssh-enabled-note
  "Note that SSH has been enabled for the build, with list of users"
  [[current-user? someone-else?] owner]
  (reify
    om/IRender
    (render [_]
      (html [:p (->> [(when current-user? "you")
                      (when someone-else? "someone other than you")]
                     (filter identity)
                     (string/join " and ")
                     (#(str % " enabled SSH for this build."))
                     (string/capitalize))]))))

(defn ssh-buttons
  "Show the enable-SSH button(s) for the SSH tab

  Includes the button to SSH to the current build if it is still running, and
  the current user hasn't already enabled SSH for themselves; always shows the
  button to rebuild with SSH enabled (for the current user.)

  Assumes that the user has permission to SSH to the build (=> these should be
  shown)"
  [build owner]
  (reify
    om/IRender
    (render [_]
      (let [build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
          [:div
           (if-not (build-model/finished? build)
             (forms/managed-button
               [:button.ssh_build
                {:data-loading-text "Adding your SSH keys..."
                 :title "Enable SSH for this build"
                 :on-click #(raise! owner [:ssh-current-build-clicked build-info])}
                "Enable SSH for this build"])
             (forms/managed-button
               [:button.ssh_build
                {:data-loading-text "Starting SSH build..."
                 :title "Retry with SSH in VM"
                 :on-click #(raise! owner [:ssh-build-clicked build-info])}
                "Retry this build with SSH enabled"]))])))))

(defn ssh-ad
  "Note about why you might want to SSH into a build and buttons to do so"
  [build owner]
    [:div.ssh-ad
     [:p "Often the best way to troubleshoot problems is to ssh into a running or finished build to look at log files, running processes, and so on."]
     (om/build ssh-buttons build)
     [:p "This will grant you ssh access to the build's containers, prevent the deploy step from starting, and keep the build up for 30 minutes after it finishes to give you time to investigate."]
     [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "ssh-build"})} "in our docs"] "."]])

(defn ssh-instructions
  "Instructions for SSHing into a build that you can SSH into"
  [build owner]
  (let [nodes (:node build)]
    (html
      [:div.ssh-info-container
       [:div.build-ssh-title
        [:p "You can SSH into this build. Use the same SSH public key that you use for GitHub. SSH boxes will stay up for 30 minutes."]
        [:p "This build takes up one of your concurrent builds, so cancel it when you are done."]]
       [:div.build-ssh-list
        [:dl.dl-horizontal
         (map (fn [node i]
                (list
                  [:dt (when (< 1 (count nodes)) [:span (str "container " i " ")])]
                  [:dd {:class (when (:ssh_enabled node) "connected")}
                   [:span (gstring/format "ssh -p %s %s@%s " (:port node) (:username node) (:public_ip_addr node))]
                   (when-not (:ssh_enabled node)
                     [:span.loading-spinner common/spinner])]))
              nodes (range))]]
       [:div.build-ssh-doc
        "Debugging Selenium browser tests? "
        [:a {:href "/docs/browser-debugging#interact-with-the-browser-over-vnc"}
         "Read our doc on interacting with the browser over VNC"]
        "."]])))

(defn build-ssh [{:keys [build user]} owner]
  (reify
    om/IRender
    (render [_]
      (let [for-current-user? (build-model/current-user-ssh? build user)
            for-someone-else? (build-model/someone-else-ssh? build user)]
        (html
          [:div
           (when (seq (:ssh_users build))
             (om/build ssh-enabled-note [for-current-user? for-someone-else?]))
           (if for-current-user?
             (cond
               (build-model/ssh-enabled-now? build) (ssh-instructions build owner)
               (build-model/finished? build) (ssh-ad build owner))
             (ssh-ad build owner))])))))

(defn build-time-visualization [build owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (-> js/console (.log "did-mount calling get-node"))
      (let [el (om/get-node owner)]
        (viz-build/visualize-timing! el build)))
    om/IRender
    (render [_]
      (js/console.log "build-time-visualization render called")
      (html
       [:div.build-time-visualization]))))

(defn cleanup-artifact-path [path]
  (-> path
      (string/replace "$CIRCLE_ARTIFACTS/" "")
      (gstring/truncateMiddle 80)))

(defn artifacts-ad []
  [:div
   [:p "We didn't find any build artifacts for this build. You can upload build artifacts by moving files to the $CIRCLE_ARTIFACTS directory."]
   [:p "Use artifacts for screenshots, coverage reports, deployment tarballs, and more."]
   [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "build-artifacts"})} "in our docs"] "."]])

(defn artifacts-tree [prefix artifacts]
  (->> (for [artifact artifacts
             :let [parts (concat [prefix]
                                 (-> (:pretty_path artifact)
                                     (string/split #"/")))]]
         [(vec (remove #{""} parts)) artifact])
       (reduce (fn [acc [parts artifact]]
                 (let [loc (interleave (repeat :children) parts)]
                   (assoc-in acc (concat loc [:artifact]) artifact)))
               {})
       :children))

(defn artifacts-node [{:keys [artifacts show-artifact-links?] :as data} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       (when (seq artifacts)
         [:ul.build-artifacts-list
          (map-indexed
           (fn node-entry [idx [part {:keys [artifact children]}]]
             (let [directory? (not artifact)
                   text       (if directory?
                                (str part "/")
                                part)
                   url        (:url artifact)
                   tag        (if (and url show-artifact-links?)
                                [:a.artifact-link {:href (:url artifact) :target "_blank"} text]
                                [:span.artifact-directory-text text])
                   key        (keyword (str "index-" idx))
                   closed?    (or
                               (:ancestors-closed? opts)
                               (om/get-state owner [key :closed?]))
                   toggler    (fn [event]
                                (let [key (keyword (str "index-" idx))]
                                  (.preventDefault event)
                                  (.stopPropagation event)
                                  (om/update-state! owner [key :closed?] not)))]
               [:li.build-artifacts-node
                (if directory?
                  [:div.build-artifacts-toggle-children
                   {:style    {:cursor  "pointer"
                               :display "inline"}
                    :on-click toggler}
                   (if closed? "▸  " "▾  ") tag]
                  tag)
                [:div {:style (when closed? {:display "none"})}
                 (om/build artifacts-node
                           {:artifacts children
                            :show-artifact-links? show-artifact-links?}
                           {:opts (assoc opts
                                    :ancestors-closed? (or (:ancestors-closed? opts) closed?))})]]))
           (sort-by first artifacts))])))))

(defn artifacts-node-v2 [{:keys [depth artifacts show-artifact-links?] :as data} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       (when (seq artifacts)
         [:ul.build-artifacts-list
          (map-indexed
           (fn node-entry [idx [part {:keys [artifact children]}]]
             (let [directory? (not artifact)
                   text       (if (and (not= 0 depth) directory?)
                                (str part "/")
                                part)
                   url        (:url artifact)
                   tag        (if (and url show-artifact-links?)
                                [:a.artifact-link {:href (:url artifact) :target "_blank"} text]
                                [:span.artifact-directory-text text])
                   key        (keyword (str "index-" idx))
                   key-state  (om/get-state owner [key])
                   closed?    (or
                               (:ancestors-closed? opts)
                               (if (contains? key-state :closed?)
                                 (:closed? key-state)
                                 (> depth 1)))
                   toggler    (fn [event]
                                (let [key (keyword (str "index-" idx))]
                                  (.preventDefault event)
                                  (.stopPropagation event)
                                  (om/set-state! owner [key :closed?] (not closed?))))]
               [:li.build-artifacts-node {:class (when (= 0 depth) "container-artifacts")}
                (if directory?
                  [:div.build-artifacts-toggle-children
                   {:style    {:cursor  "pointer"
                               :display "inline"}
                    :on-click toggler}
                   [:i.fa.artifact-toggle-caret
                    {:class (if closed? "fa-angle-right" "fa-angle-down")}]
                   " "
                   tag]
                  tag)
                [:div {:style (when closed? {:display "none"})}
                 (om/build artifacts-node-v2
                           {:depth (+ depth 1)
                            :artifacts children
                            :show-artifact-links? show-artifact-links?}
                           {:opts (assoc opts
                                    :ancestors-closed? (or (:ancestors-closed? opts) closed?))})]]))
           (sort-by first artifacts))])))))

(defn should-show-artifact-links?
  ;; Be extra careful about XSS in production environment
  [env admin?]
  (or (not= env "production")
      (not admin?)))

(defn build-artifacts-list [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:artifacts-showed]))

    om/IRender
    (render [_]
      (let [artifacts-data (:artifacts-data data)
            artifacts (:artifacts artifacts-data)
            has-artifacts? (:has-artifacts? data)]
        (html
         [:div.build-artifacts-container
          (if-not has-artifacts?
            (artifacts-ad)
            (if artifacts
              (map (fn artifact-node-builder [[node-index node-artifacts]]
                     (om/build artifacts-node {:artifacts (artifacts-tree (str "Container " node-index) node-artifacts)
                                               :show-artifact-links? (should-show-artifact-links? (env) (:admin (:user data)))}))
                   (->> artifacts
                        (group-by :node_index)
                        (sort-by first)))
              [:div.loading-spinner common/spinner]))])))))

(defn build-artifacts-list-v2 [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:artifacts-showed]))

    om/IRender
    (render [_]
      (let [artifacts-data (:artifacts-data data)
            artifacts (:artifacts artifacts-data)
            has-artifacts? (:has-artifacts? data)]
        (html
         [:div.build-artifacts-container
          (if-not has-artifacts?
            (artifacts-ad)
            (if artifacts
              (interpose [:hr]
                         (map (fn artifact-node-builder [[node-index node-artifacts]]
                                (om/build artifacts-node-v2 {:artifacts (artifacts-tree (str "Container " node-index) node-artifacts)
                                                             :depth 0
                                                             :show-artifact-links? (should-show-artifact-links? (env) (:admin (:user data)))}
                                          ))
                              (->> artifacts
                                   (group-by :node_index)
                                   (sort-by first))))
              [:div.loading-spinner common/spinner]))])))))

(defn tests-ad [owner]
  [:div
   "With some of our inferred build commands we collect test metadata automatically, but that didn't happen for this project.  Collecting metadata allows us to list the specific failures, and in some cases makes parallel builds more efficient.  Here's how to get it:"
   [:ul
    [:li "For an inferred ruby test command, simply add the necessary "
     [:a {:href "/docs/test-metadata#automatic-test-metadata-collection"} "formatter gem"]]
    [:li "Python should work automatically, except for django you'll need to " [:a {:href "/docs/test-metadata#automatic-test-metadata-collection"} "use django-nose"] "."]
    [:li "For another inferred test runner that you'd like us to add metadata support for, "
     [:a (common/contact-support-a-info owner)
      "let us know"] "."]
    [:li "For a custom test command, configure your test runner to write a JUnit XML report to a directory in $CIRCLE_TEST_REPORTS - see "
     [:a {:href "/docs/test-metadata#metadata-collection-in-custom-test-steps"} "the docs"] " for more information."]]])

(defmulti format-test-name-v2 test-model/source)

(defmethod format-test-name-v2 :default [test]
  (->> [[(:name test)] [(:classname test) (:file test)]]
       (map (fn [s] (some #(when-not (string/blank? %) %) s)))
       (filter identity)
       (string/join " - in ")))

(defmethod format-test-name-v2 "lein-test" [test]
  [:strong.build-test-name (str (:classname test) "/" (:name test))])

(defmethod format-test-name-v2 "cucumber" [test]
  [:strong.build-test-name (if (string/blank? (:name test))
             (:classname test)
             (:name test))])

(defn test-item [test owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:li
         (test-model/format-test-name test)
         (when-not (string/blank? (:message test))
           [:a {:role "button"
                :on-click #(raise! owner [:show-test-message-toggled {:test-index (:i test)}])}
            " more info "
            (if (:show-message test) [:i.fa.fa-caret-up] [:i.fa.fa-caret-down])])
         (when (:show-message test)
           [:pre (:message test)])]))))

(defn test-item-v2 [test owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:li.build-test
         (format-test-name-v2 test)
         " - "
         (when-not (string/blank? (:message test))
           [:a.test-output-toggle {:role "button"
                                   :on-click #(raise! owner [:show-test-message-toggled {:test-index (:i test)}])}
            (if (:show-message test) "less info" "more info")])
         (when (:show-message test)
           [:pre.build-test-output (:message test)])]))))

(defn build-tests-list [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:tests-showed]))

    om/IRender
    (render [_]
      (let [tests-data (:tests-data data)
            tests (when (:tests tests-data)
                    (map-indexed #(assoc %2 :i %1) (:tests tests-data)))
            sources (reduce (fn [s test] (conj s (test-model/source test))) #{} tests)
            failed-tests (filter #(contains? #{"failure" "error"} (:result %)) tests)]
        (html
         [:div.build-tests-container
          (if-not tests
            [:div.loading-spinner common/spinner]
            (if (empty? tests)
              (tests-ad owner)
              [:div.build-tests-info
               [:div.build-tests-summary
                (str "Your build ran " (pluralize (count tests) "test") " in "
                     (string/join ", " (map test-model/pretty-source sources))
                     " with " (pluralize (count failed-tests) "failure") ".")]
               (when (seq failed-tests)
                 (for [[source tests-by-source] (group-by test-model/source failed-tests)]
                   [:div.build-tests-list-container
                    [:span.failure-source (str (test-model/pretty-source source) " failures:")]
                    [:ol.build-tests-list
                     (for [[file tests-by-file] (group-by :file tests-by-source)]
                       (list (when file [:div.filename (str file ":")])
                             (om/build-all test-item
                                           (vec (sort-by test-model/format-test-name tests-by-file)))))]]))]))])))))

(def initial-test-render-count 5)

(defn build-tests-list-v2 [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:tests-showed]))

    om/IRender
    (render [_]
      (let [tests-data (:tests-data data)
            tests (when (:tests tests-data)
                    (map-indexed #(assoc %2 :i %1) (:tests tests-data)))
            sources (reduce (fn [s test] (conj s (test-model/source test))) #{} tests)
            failed-tests (filter #(contains? #{"failure" "error"} (:result %)) tests)
            build-succeeded? (= "success" (get-in data [:build :status]))]
        (html
         [:div.test-results
          (if-not tests
            [:div.loading-spinner common/spinner]
            (cond
              (seq failed-tests) (for [[source tests-by-source] (group-by test-model/source failed-tests)]
                                   [:div.alert.alert-danger.expanded.build-tests-info
                                    [:div.alert-header
                                     [:img.alert-icon {:src (common/icon-path "Info-Error")}]
                                     (test-model/pretty-source source)
                                     " - "
                                     (pluralize (count failed-tests) "failure")]
                                    [:div.alert-body
                                     [:div.build-tests-summary
                                      "Your build ran "
                                      [:strong (pluralize (count tests) "test")]
                                      " with "
                                      [:strong (pluralize (count failed-tests) "failure")]]
                                     [:div.build-tests-list-container
                                      [:ol.list-unstyled.build-tests-list
                                       (for [[file tests-by-file] (group-by :file tests-by-source)]
                                         (let [sorted-tests (sort-by test-model/format-test-name tests-by-file)
                                               initial-test-results (take initial-test-render-count sorted-tests)
                                               other-tests (drop initial-test-render-count sorted-tests)]

                                         (list (when file [:div.filename (str file ":")])
                                               (om/build-all test-item-v2
                                                             (vec initial-test-results))
                                               (when (seq other-tests)
                                                 (list
                                                   [:hr]
                                                   [:li
                                                    [:button.btn-link.build-tests-toggle {:on-click #(om/update-state! owner [:is-open?] not)}
                                                     (if (om/get-state owner :is-open?)
                                                       [:span
                                                        [:i.fa.fa-chevron-up.build-tests-toggle-icon]
                                                        "Less"]
                                                       [:span 
                                                        [:i.fa.fa-chevron-down.build-tests-toggle-icon]
                                                        "More"])]]
                                                   (when (om/get-state owner :is-open?)
                                                     (om/build-all test-item-v2
                                                                   (vec other-tests))))))))]]]])

              (seq tests) [:div
                           "Your build ran "
                           [:strong (count tests)]
                           " tests in " (string/join ", " (map test-model/pretty-source sources)) " with "
                           [:strong "0 failures"]]

              :else [:div.alert.iconified {:class (if build-succeeded? "alert-info" "alert-danger")}
                     [:div [:img.alert-icon {:src (common/icon-path
                                                    (if build-succeeded? "Info-Info" "Info-Error"))}]]
                     (tests-ad owner)]))])))))

(defn circle-yml-ad []
  [:div
   [:p "We didn't find a circle.yml for this build. You can specify deployment or override our inferred test steps from a circle.yml checked in to your repo's root directory."]
   [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "configuration"})} "in our docs"] "."]])

(defn build-config [{:keys [config-string]} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       (if (seq config-string)
         [:div.build-config-string [:pre config-string]]
         (circle-yml-ad))))))

(defn build-parameters [{:keys [build-parameters]} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.build-parameters
        [:pre (for [[k v] build-parameters
                    :let [pname (name k) pval (pr-str v)]]
                (str pname "=" pval \newline))]]))))

(defn expected-duration
  [{:keys [start stop build]} owner opts]
  (reify

    om/IDisplayName
    (display-name [_] "Expected Duration")

    om/IDidMount
    (did-mount [_]
      (timer/set-updating! owner (not stop)))

    om/IDidUpdate
    (did-update [_ _ _]
      (timer/set-updating! owner (not stop)))

    om/IRender
    (render [_]
      (let [end-ms (if stop
                     (.getTime (js/Date. stop))
                     (datetime/server-now))
            formatter (get opts :formatter datetime/as-duration)
            duration-ms (- end-ms (.getTime (js/Date. start)))
            previous-build (:previous_successful_build build)
            past-ms (:build_time_millis previous-build)]
        (if (and past-ms
                 (= (:status build) "running")
                 (< duration-ms (* 1.5 past-ms)))
          (dom/span nil "/~" (formatter past-ms))
          (dom/span nil ""))))))

(def tab-link :a.tab-link)

(defn build-sub-head [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            scopes (:scopes data)
            user (:user data)
            logged-in? (not (empty? user))
            admin? (:admin user)
            build (:build build-data)
            selected-tab (:selected-header-tab build-data)
            selected-container (:selected-container data)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            config-data (:config-data build-data)
            build-params (:build_parameters build)]
        (html
          [:div.sub-head
           [:div.sub-head-top
            [:ul.nav.nav-tabs
             [:li {:class (when (= :commits selected-tab) "active")}
              [tab-link {:href (str "#" (build/to-fragment "commits" selected-container))} "Commit Log"]]

            [:li {:class (when (= :config selected-tab) "active")}
             [tab-link {:href (str "#" (build/to-fragment "config" selected-container))} "circle.yml"]]

            (when (seq build-params)
              [:li {:class (when (= :build-parameters selected-tab) "active")}
               [tab-link {:href (str "#" (build/to-fragment "build-parameters" selected-container))} "Build Parameters"]])

            (when (has-scope :read-settings data)
              [:li {:class (when (= :usage-queue selected-tab) "active")}
               [tab-link {:id "queued_explanation"
                          :href (str "#" (build/to-fragment "usage-queue" selected-container))} "Queue"
                (when (:usage_queued_at build)
                  [:span " ("
                   (om/build common/updating-duration {:start (:usage_queued_at build)
                                                       :stop (or (:start_time build) (:stop_time build))})
                   ")"])]])

            ;; XXX Temporarily remove the ssh info for OSX builds
            (when (and (has-scope :write-settings data)
                       (not (feature/enabled-for-project? project :osx)))
              [:li {:class (when (= :ssh-info selected-tab) "active")}
               [tab-link {:href (str "#" (build/to-fragment "ssh-info" selected-container))} "Debug via SSH"]])

            ;; tests don't get saved until the end of the build (TODO: stream the tests!)
            (when (build-model/finished? build)
              [:li {:class (when (= :tests selected-tab) "active")}
               [tab-link {:href (str "#" (build/to-fragment "tests" selected-container))} (if (= "success" (:status build))
                                      "Test Results "
                                      "Test Failures ")
                (when-let [fail-count (some->> build-data
                                               :tests-data
                                               :tests
                                               (filter #(contains? #{"failure" "error"} (:result %)))
                                               count)]
                  (when (not= 0 fail-count)
                    [:span {:class "fail-count"} fail-count]))]])

            (when (and admin? (build-model/finished? build))
              [:li {:class (when (= :build-time-viz selected-tab) "active")}
               [tab-link {:href (str "#" (build/to-fragment "build-time-viz" selected-container))} "Build Timing"]])

            ;; artifacts don't get uploaded until the end of the build (TODO: stream artifacts!)
            (when (and logged-in? (build-model/finished? build))
              [:li {:class (when (= :artifacts selected-tab) "active")}
               [tab-link {:href (str "#" (build/to-fragment "artifacts" selected-container))} "Artifacts"]])]]

          [:div.sub-head-content
           (case selected-tab
             :commits (om/build build-commits build-data)

             :tests (om/build build-tests-list build-data)

             :build-time-viz (om/build build-time-visualization build)

             :artifacts (om/build build-artifacts-list
                                  {:artifacts-data (get build-data :artifacts-data) :user user
                                   :has-artifacts? (:has_artifacts build)})

             :config (om/build build-config {:config-string (get-in build [:circle_yml :string])})

             :build-parameters (om/build build-parameters {:build-parameters build-params})

             :usage-queue (om/build build-queue {:build build
                                                 :builds (:builds usage-queue-data)
                                                 :plan plan})
             :ssh-info (om/build build-ssh {:build build :user user})

             ;; avoid errors if a nonexistent tab is typed in the URL
             nil)]])))))

(defn link-to-user [build]
  (when-let [user (:user build)]
    [:a {:href (gh-utils/login-url (:login user))}
     (build-model/ui-user build)]))

(defn link-to-commit [build]
  [:a {:href (:compare build)}
   (take 7 (:vcs_revision build))])

(defn link-to-retry-source [build]
  (when-let [retry-id (:retry_of build)]
    [:a {:href (gstring/format "/gh/%s/%s/%d"
                               (:username build)
                               (:reponame build)
                               retry-id)}
     retry-id]))

(defn trigger-html [build]
  (let [user-link (link-to-user build)
        commit-link (link-to-commit build)
        retry-link (link-to-retry-source build)
        cache? (build-model/dependency-cache? build)]
    (case (:why build)
      "github" (list user-link " (pushed " commit-link ")")
      "edit" (list user-link " (updated project settings)")
      "first-build" (list user-link " (first build)")
      "retry"  (list user-link " (retried " retry-link
                                (when-not cache? " without cache")")")
      "ssh" (list user-link " (retried " retry-link " with SSH)")
      "auto-retry" (list "CircleCI (auto-retry of " retry-link ")")
      "trigger" (if (:user build)
                  (list user-link " on CircleCI.com")
                  (list "CircleCI.com"))
      "api" "API"
      (or
        (:job_name build)
        "unknown"))))

(defn build-head [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            build (:build build-data)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            user (:user data)
            logged-in? (not (empty? user))
            config-data (:config-data build-data)
            build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
         [:div.build-head-wrapper
          [:div.build-head
           [:div.build-info
            [:table
             [:tbody
              [:tr
               [:th "Triggered by"]
               [:td (trigger-html build)]
               [:th "Started"]
               [:td (when (:start_time build)
                      {:title (datetime/full-datetime (:start_time build))})
                (when (:start_time build)
                  (list (om/build common/updating-duration
                                  {:start (:start_time build)}
                                  {:opts {:formatter datetime/time-ago}}) " ago"))]]
              [:tr
               [:th "Previous"]
               (if-not (:previous build)
                 [:td "none"]
                 [:td
                  [:a {:href (routes/v1-build-path (vcs-url/org-name vcs-url) (vcs-url/repo-name vcs-url) (:build_num (:previous build)))}
                   (:build_num (:previous build))]])

               [:th "Duration"]
               [:td (if (build-model/running? build)
                      (om/build common/updating-duration {:start (:start_time build)
                                                          :stop (:stop_time build)})
                      (build-model/duration build))
                    (om/build expected-duration {:start (:start_time build)
                                                :stop (:stop_time build)
                                                :build build})]]
              [:tr
               (when (:usage_queued_at build)
                 (list [:th "Queued"]
                       [:td (if (< 0 (build-model/run-queued-time build))
                              [:span
                               (om/build common/updating-duration {:start (:usage_queued_at build)
                                                                   :stop (or (:queued_at build) (:stop_time build))})
                               " waiting + "
                               (om/build common/updating-duration {:start (:queued_at build)
                                                                   :stop (or (:start_time build) (:stop_time build))})
                               " in queue"]

                              [:span
                               (om/build common/updating-duration {:start (:usage_queued_at build)
                                                                   :stop (or (:queued_at build) (:stop_time build))})
                               " waiting for builds to finish"])]))
               [:th "Status"]
               [:td
                [:span.build-status {:class (:status build)}
                 (build-model/status-words build)]
                (when-let [canceler (and (= (:status build) "canceled")
                                         (:canceler build))]
                  [:span.build-canceler
                   (list "by "
                         [:a {:href (str (github-endpoint) "/" (:login canceler))}
                          (if (not-empty (:name canceler))
                            (:name canceler)
                            (:login canceler))])])]]
              [:tr
              [:th "Parallelism"]
               [:td
                (if (has-scope :write-settings data)
                  [:a.parallelsim-link-head {:title (str "This build used " (:parallel build) " containers. Click here to change parallelism for future builds.")
                                             :href (build-model/path-for-parallelism build)}
                   (str (:parallel build) "x")]
                  [:span (:parallel build) "x"])]

               (when-let [urls (seq (:pull_request_urls build))]
                 ;; It's possible for a build to be part of multiple PRs, but it's rare
                 (list [:th (str "PR" (when (< 1 (count urls)) "s"))]
                       [:td
                        (interpose
                         ", "
                         (map (fn [url] [:a {:href url} "#"
                                         (let [n (re-find #"/\d+$" url)]
                                           (if n (subs n 1) "?"))])
                              urls))]))
               ]


            [:tr
             [:th "Author"]
             [:td (if-not (:author_email build)
                    [:span (build-model/author build)]
                    [:a {:href (str "mailto:" (:author_email build))}
                     (build-model/author build)])]
             (when (build-model/author-isnt-committer build)
                 (list [:th "Committer"]
                       [:td
                        (if-not (:committer_email build)
                          [:span (build-model/committer build)]
                          [:a {:href (str "mailto:" (:committer_email build))}
                           (build-model/committer build)])]))]

              ]]


            [:div.build-actions
             (when (has-scope :write-settings data)
               [:div.actions
                (forms/managed-button
                 [:button.retry_build
                  {:data-loading-text "Rebuilding",
                   :title "Retry the same tests",
                   :on-click #(raise! owner [:retry-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num
                                                                   :no-cache? false}])}
                  "Rebuild"])

                (forms/managed-button
                 [:button.without_cache_retry
                  {:data-loading-text "Rebuilding",
                   :title "Retry without cache",
                   :on-click #(raise! owner [:retry-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num
                                                                   :no-cache? true}])}
                  "without cache"])

                ;; XXX Temporarily remove the ssh button for OSX builds
                (when (not (feature/enabled-for-project? project :osx))
                  (forms/managed-button
                   [:button.ssh_build
                    {:data-loading-text "Rebuilding",
                     :title "Retry with SSH in VM",
                     :on-click #(raise! owner [:ssh-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num}])}
                    "with ssh"]))])
             [:div.actions
              ;; TODO: Handle when intercom isn't enabled
              (when (and logged-in? (intercom-enabled?)) ;; no intercom for logged-out users
                [:button.report_build
                 {:title "Report error with build",
                  :on-click #(raise! owner [:report-build-clicked {:build-url (:build_url @build)}])}
                 "Report"])
              (when (and (build-model/can-cancel? build) (has-scope :write-settings data))
                (forms/managed-button
                  [:button.cancel_build
                   {:data-loading-text "Canceling",
                    :title "Cancel this build",
                    :on-click #(raise! owner [:cancel-build-clicked {:build-id build-id
                                                                     :vcs-url vcs-url
                                                                     :build-num build-num}])}
                   "Cancel"]))]]
[:div.no-user-actions]]

           (om/build build-sub-head data)]])))))

(defn commit-line-v2 [{:keys [author_name build subject body commit_url commit] :as commit-details} owner]
  (let [author-icon [:img.dashboard-icon {:src (common/icon-path "Builds-Author")}]]
    (reify
      om/IDidMount
      (did-mount [_]
        (when (seq body)
          (utils/tooltip (str "#commit-line-tooltip-hack-" commit)
                         {:placement "bottom"
                          :animation false
                          :viewport "#build-log-container"})))
      om/IRender
      (render [_]
        (html
          [:div
           [:span.metadata-item
            (if-not (:author_email commit-details)
              [:span
               author-icon
               (build-model/author commit-details)]
              [:a {:href (str "mailto:" (:author_email commit-details))}
               author-icon
               (build-model/author commit-details)])]
           (when (build-model/author-isnt-committer commit-details)
             [:span.metadata-item
              (if-not (:committer_email commit-details)
                [:span
                 author-icon
                 (build-model/committer commit-details)]
                [:a {:href (str "mailto:" (:committer_email commit-details))}
                 author-icon
                 (build-model/committer commit-details)])])

           [:a.metadata-item.sha-one {:href commit_url
                                      :title commit}
            [:img.dashboard-icon {:src (common/icon-path "Builds-CommitNumber")}]
            (subs commit 0 7)]
           [:span.commit-message
            {:title body
             :id (str "commit-line-tooltip-hack-" commit)
             :dangerouslySetInnerHTML {:__html (-> subject
                                                   (gstring/htmlEscape)
                                                   (linkify)
                                                   (maybe-project-linkify (vcs-url/project-name (:vcs_url build))))}}]])))))

(defn build-commits-v2 [build-data owner]
  (reify
    om/IRender
    (render [_]
      (let [build (:build build-data)
            build-id (build-model/id build)]
        (html
         [:div.build-commits-container
          (when (:subject build)
            [:div.build-commits-list
             (if-not (seq (:all_commit_details build))
               (om/build commit-line-v2 {:build build
                                         :subject (:subject build)
                                         :body (:body build)
                                         :commit_url (build-model/github-commit-url build)
                                         :commit (:vcs_revision build)})
               (list
                 (om/build-all commit-line-v2 (take 3 (map #(assoc % :build build)
                                                           (:all_commit_details build))))

                 (when (< 3 (count (:all_commit_details build)))
                   (list
                     [:hr]
                     [:a {:role "button"
                          :on-click #(raise! owner [:show-all-commits-toggled {:build-id build-id}])}
                      (if (:show-all-commits build-data)
                        [:i.fa.fa-caret-up]
                        [:i.fa.fa-caret-down])
                      " More"]))

                 (when (:show-all-commits build-data)
                   (om/build-all commit-line-v2 (drop 3 (map #(assoc % :build build)
                                                             (:all_commit_details build)))))))])])))))


(def tab-tag :li.build-info-tab)
(def tab-link-v2 :a.tab-link-v2)

(defn build-sub-head-v2 [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            scopes (:scopes data)
            user (:user data)
            logged-in? (not (empty? user))
            admin? (:admin user)
            build (:build build-data)
            selected-tab (:selected-header-tab build-data)
            selected-container (:selected-container data)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            config-data (:config-data build-data)
            build-params (:build_parameters build)]
        (html
         [:div.sub-head
          [:div.sub-head-top
           [:ul.nav.nav-tabs
            ;; tests don't get saved until the end of the build (TODO: stream the tests!)
            (when (build-model/finished? build)
              [tab-tag {:class (when (= :tests selected-tab) "active")}
               [tab-link-v2 {:href (str "#" (build/to-fragment "tests" selected-container))}
                (if (= "success" (:status build))
                  "Test Results "
                  "Test Failures ")
                (when-let [fail-count (some->> build-data
                                               :tests-data
                                               :tests
                                               (filter #(contains? #{"failure" "error"} (:result %)))
                                               count)]
                  (when (not= 0 fail-count)
                    [:span "(" fail-count ")"]))]])

            (when (has-scope :read-settings data)
              [tab-tag {:class (when (= :usage-queue selected-tab) "active")}
               [tab-link-v2 {:id "queued_explanation"
                             :href (str "#" (build/to-fragment "usage-queue" selected-container))}
                "Queue"
                (when (:usage_queued_at build)
                  [:span " ("
                   (om/build common/updating-duration {:start (:usage_queued_at build)
                                                       :stop (or (:start_time build) (:stop_time build))})
                   ")"])]])

            ;; XXX Temporarily remove the ssh info for OSX builds
            (when (and (has-scope :write-settings data)
                       (not (feature/enabled-for-project? project :osx)))
              [tab-tag {:class (when (= :ssh-info selected-tab) "active")}
               [tab-link-v2 {:href (str "#" (build/to-fragment "ssh-info" selected-container))}
                "Debug via SSH"]])

            ;; artifacts don't get uploaded until the end of the build (TODO: stream artifacts!)
            (when (and logged-in? (build-model/finished? build))
              [tab-tag {:class (when (= :artifacts selected-tab) "active")}
               [tab-link-v2 {:href (str "#" (build/to-fragment "artifacts" selected-container))}
                "Artifacts"]])

            [tab-tag {:class (when (= :config selected-tab) "active")}
             [tab-link-v2 {:href (str "#" (build/to-fragment "config" selected-container))} "circle.yml"]]

            (when (and admin? (build-model/finished? build))
              [tab-tag {:class (when (= :build-time-viz selected-tab) "active")}
               [tab-link-v2 {:href (str "#" (build/to-fragment "build-time-viz" selected-container))}
                "Build Timing"]])

            (when (seq build-params)
              [tab-tag {:class (when (= :build-parameters selected-tab) "active")}
               [tab-link-v2 {:href (str "#" (build/to-fragment "build-parameters" selected-container))} "Build Parameters"]])]]

          [:div.card.sub-head-content {:class (str "sub-head-" (name selected-tab))}
           (case selected-tab

             :tests (om/build build-tests-list-v2 build-data)

             :build-time-viz (om/build build-time-visualization build)

             :artifacts (om/build build-artifacts-list-v2
                                  {:artifacts-data (get build-data :artifacts-data) :user user
                                   :has-artifacts? (:has_artifacts build)})

             :config (om/build build-config {:config-string (get-in build [:circle_yml :string])})

             :build-parameters (om/build build-parameters {:build-parameters build-params})

             :usage-queue (om/build build-queue-v2 {:build build
                                                    :builds (:builds usage-queue-data)
                                                    :plan plan})
             :ssh-info (om/build build-ssh {:build build :user user})

             ;; avoid errors if a nonexistent tab is typed in the URL
             nil)]])))))

(defn build-canceler [canceler github-endpoint]
  [:span.summary-label
   (list "Canceled by: "
         [:a  {:href  (str  (github-endpoint) "/"  (:login canceler))}
          (if  (not-empty  (:name canceler))
            (:name canceler)
            (:login canceler))])])

(defn pull-requests [urls]
  ;; It's possible for a build to be part of multiple PRs, but it's rare
  (list
    [:span.summary-spacer "•"]
    [:span.summary-label
     (str "Pull Request" (when (< 1 (count urls)) "s") ": ")]
    [:span
     (interpose
       ", "
       (map (fn [url] [:a {:href url} "#"
                       (let [n (re-find #"/\d+$" url)]
                         (if n (subs n 1) "?"))])
            urls))]))

(defn queued-time [build]
  (if (< 0 (build-model/run-queued-time build))
    [:span
     (om/build common/updating-duration {:start (:usage_queued_at build)
                                         :stop (or (:queued_at build) (:stop_time build))})
     " waiting + "
     (om/build common/updating-duration {:start (:queued_at build)
                                         :stop (or (:start_time build) (:stop_time build))})
     " in queue"]

    [:span
     (om/build common/updating-duration {:start (:usage_queued_at build)
                                         :stop (or (:queued_at build) (:stop_time build))})
     " waiting for builds to finish"]))

(defn build-finished-status [build]
  (let [stop-time  (:stop_time build)
        start-time (:start_time build)]
    [:div.summary-item
     [:span.summary-label "Finished: "]
     [:span.stop-time
      (when stop-time
        {:title (datetime/full-datetime stop-time)})
      (when stop-time
        (list (om/build common/updating-duration
                        {:start stop-time}
                        {:opts {:formatter datetime/time-ago}}) " ago"))]
     [:span
      " ("
      (if (build-model/running? build)
        (om/build common/updating-duration {:start start-time
                                            :stop  stop-time})
        (build-model/duration build))
      (om/build expected-duration {:start start-time
                                   :stop  stop-time
                                   :build build})
      ")"]]))

(defrender previous-build-label [{:keys [previous vcs_url]} owner]
  (when-let [build-number (:build_num previous)]
    (html
      [:div.summary-item
       [:span.summary-label "Previous: "]
       [:a {:href (routes/v1-build-path (vcs-url/org-name vcs_url) (vcs-url/repo-name vcs_url) build-number)}
          build-number]])))

(defn build-head-v2 [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            build (:build build-data)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            user (:user data)
            logged-in? (not (empty? user))
            config-data (:config-data build-data)
            build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
          [:div
           [:div.summary-header
            [:div.summary-items
             [:div.summary-item
              (builds-table/build-status-badge build)]
             (when (:stop_time build)
               (build-finished-status build))]
            [:div.summary-items
             (om/build previous-build-label build)
             [:div.summary-item
              [:span.summary-label "Parallelism: "]
              (if (has-scope :write-settings data)
                [:a.parallelsim-link-head {:title (str "This build used " (:parallel build) " containers. Click here to change parallelism for future builds.")
                                           :href (build-model/path-for-parallelism build)}
                 (str (:parallel build) "x")]
                [:span (:parallel build) "x"])]]
            (when (:usage_queued_at build)
              [:div.summary-items
               [:div.summary-item
                [:span.summary-label "Queued: "]
                [:span  (queued-time build)]]])

            [:div.summary-build-contents
             [:div.summary-item
              [:span.summary-label "Triggered by: "]
              [:span (trigger-html build)]]

             (when-let [urls (seq (:pull_request_urls build))]
               (pull-requests urls))]]

           (when-let  [canceler  (and  (=  (:status build) "canceled")
                                      (:canceler build))]
             [:div.summary-header
              [:div.summary-items
               [:div.summary-item
                (build-canceler canceler github-endpoint)]]])
           [:div.card
            [:div.small-emphasis "Commits (" (-> build :all_commit_details count) ")"]
            (om/build build-commits-v2 build-data)]
           [:div.build-head-wrapper
            [:div.build-head
             (om/build build-sub-head-v2 data)]]])))))

(defn rebuild-actions-v2 [{:keys [build project]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:rebuild-status "Rebuild"})

    om/IWillUpdate
    (will-update [_ {:keys [build]} _]
      (when (build-model/running? build)
        (om/set-state! owner [:rebuild-status] "Rebuild")))

    om/IRenderState
    (render-state [_ {:keys [rebuild-status]}]
      (let [rebuild-args    {:build-id  (build-model/id build)
                             :vcs-url   (:vcs_url build)
                             :build-num (:build_num build)}
            update-status!  #(om/set-state! owner [:rebuild-status] %)
            rebuild!        #(raise! owner %)
            actions         {:rebuild
                             {:text  "Rebuild with cache"
                              :title "Retry the same tests"
                              :action #(do (rebuild! [:retry-build-clicked (merge rebuild-args {:no-cache? false})])
                                           (update-status! "Rebuilding with cache"))}

                             :without_cache
                             {:text  "Rebuild without cache"
                              :title "Retry without cache"
                              :action #(do (rebuild! [:retry-build-clicked (merge rebuild-args {:no-cache? true})])
                                           (update-status! "Rebuilding without cache"))}

                             :with_ssh
                             {:text  "Rebuild with SSH"
                              :title "Retry with SSH in VM",
                              :action #(do (rebuild! [:ssh-build-clicked rebuild-args])
                                           (update-status! "Rebuilding with SSH"))}}
            text-for    #(-> actions % :text)
            action-for  #(-> actions % :action)]
        (html
          [:div.dropdown.rebuild
           [:button.btn.dropdown-toggle {:data-toggle "dropdown"}
            [:span.status rebuild-status]
            (when (= rebuild-status "Rebuild")
              [:img.chevron {:src (common/icon-path "UI-ArrowChevron")}])]
           [:ul.dropdown-menu
            [:li
             [:a {:on-click (action-for :rebuild)} (text-for :rebuild)]]
            [:li
             [:a {:on-click (action-for :without_cache)} (text-for :without_cache)]]
            ;; XXX Temporarily remove the ssh button for OSX builds
            (when (not (feature/enabled-for-project? project :osx))
              [:li
               [:a {:on-click (action-for :with_ssh)} (text-for :with_ssh)]])]])))))

(defn build-head-actions
  [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (dissoc (get-in data state/build-data-path) :container-data)
            build (get-in data state/build-path)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            project (get-in data state/project-data-path)
            plan (:plan project)
            user (get-in data state/user-path)
            logged-in? (not (empty? user))
            has-write-settings? (:write-settings
                                  (get-in data state/project-scopes-path))]
        (html
          [:div.build-actions-v2
           (when (and (build-model/can-cancel? build) has-write-settings?)
             (forms/managed-button
               [:a.build-action
                {:data-loading-text "Canceling"
                 :title             "Cancel this build"
                 :on-click #(raise! owner [:cancel-build-clicked {:build-id build-id
                                                                  :vcs-url vcs-url
                                                                  :build-num build-num}])}
                "Cancel Build"]))
           (when has-write-settings?
             (om/build rebuild-actions-v2 {:build build :project project}))
           [:a.build-action
            {:href (routes/v1-project-settings {:org  (get-in data (conj state/project-plan-path :org_name))
                                                :repo (get-in data (conj state/project-path :reponame))})}
            [:img.dashboard-icon {:src (common/icon-path "QuickLink-Settings")}]
            "Project Settings"]])))))
