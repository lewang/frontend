(ns circleci.backend.load-balancer
  (:import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
           com.amazonaws.services.ec2.AmazonEC2Client
           (com.amazonaws.services.elasticloadbalancing.model
            ConfigureHealthCheckRequest
            DeleteLoadBalancerRequest
            HealthCheck
            Instance
            Listener
            RegisterInstancesWithLoadBalancerRequest
            CreateLoadBalancerRequest))
  (:use [circleci.aws-credentials :only (aws-credentials)])
  (:use [pallet.thread-expr :only (when->)])
  (:use [circleci.utils.except :only (throw-if-not)])
  (:use [circleci.utils.core :only (apply-map)]))

(defn availability-zones []
  (-> (AmazonEC2Client. aws-credentials)
      (.describeAvailabilityZones)
      (.getAvailabilityZones)
      (->>
       (map (fn [az]
              {:zone (.getZoneName az)
               :region (.getRegionName az)
               :status (.getState az)})))))

(defmacro with-client
  [client & body]
  `(let [~client (AmazonElasticLoadBalancingClient. aws-credentials)]
     ~@body))

(defn delete-balancer
  "name is the name passed to create-balancer. Returns nil"
  [name]
  (with-client client
    (.deleteLoadBalancer client (DeleteLoadBalancerRequest. name))))

(defn register-instances-with-balancer
  "Instances is a seq of strings, each an EC2 instance id"
  [lb-name instances]
  (with-client client
    (.registerInstancesWithLoadBalancer
     client
     (RegisterInstancesWithLoadBalancerRequest. lb-name
                                                (for [i instances]
                                                  (Instance. i))))))

(defn configure-health-check
  "Instructs the load balancer how to determine when the EC2 instances are healthy.

   target - what to ping. in the format 'protocol:port' Protocol can be one of TCP, HTTP, HTTPS, or SSL. When the protocol is HTTP(S), should be in the format 'HTTP:80/path/to/request'. Any status other than 200 is considered unhealthy.
   interval - how often to ping, in seconds
   timeout - how long to wait before a ping fails.
   unhealthy-threshold - number of failed pings before the instance is marked unhealthy and disabled from the load balancer.
   healthy-threshold - number of successful pings before the instance is restored to the load balancer "
  [lb-name & {:keys [target interval timeout unhealthy-threshold healthy-threshold]}]
  (with-client client
    (.configureHealthCheck client
                           (ConfigureHealthCheckRequest. lb-name
                                                         (HealthCheck. target
                                                                       interval
                                                                       timeout
                                                                       unhealthy-threshold
                                                                       healthy-threshold)))))

(defn create-balancer
  "Create a new load balancer. Returns a string, the DNS name of the load balancer.

  listeners - a seq of maps, each map should have keys :load-balancer-port, :instance-port, :protocol

  availability-zones - a seq of strings

  health-check - (optional) a map, taking the same options as configure-health-check

  "
  [& {:keys [name
             listeners
             availability-zones
             health-check]}]
  (with-client client
    (let [request (CreateLoadBalancerRequest.)]

      (throw-if-not name "name is required")
      (throw-if-not listeners "listeners is required")
      (throw-if-not availability-zones "availability-zones is required")

      (doto request
        (.setLoadBalancerName name)
        (.setListeners (for [listener listeners]
                         (Listener. (-> listener :protocol)
                                    (-> listener :load-balancer-port)
                                    (-> listener :instance-port))))
        (.setAvailabilityZones availability-zones))
      
      (-> client 
          (.createLoadBalancer request)
          (.getDNSName))

      (when health-check
        (apply-map configure-health-check name health-check)))))