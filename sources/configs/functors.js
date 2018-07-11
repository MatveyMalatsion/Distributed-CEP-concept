Array.prototype.contains = function(obj) {
    var i = this.length;
    while (i--) {
        if (this[i] === obj) {
            return true;
        }
    }
    return false;
}

function not_wifi_presence_status(value){
    return !wifi_presence_status(value)
}

function not_appliences_presence_status(value){
    return !appliences_presence_status(value)
}

function is_network(value){
    return value.type == "networkPresence"
}

function is_app(value){
    return value.type == "appliances"
}

function wifi_presence_status(value){ 
    for(var i = 0; i < value.knownHosts.length; i++){
        host = value.knownHosts[i]
        
        for(var j = 0; j < value.reacheableHosts.length; j++){
            
            rHost = value.reacheableHosts
            
            if(rHost == host){
                return true
            }
        }

    }
    return false
}

function raise_presence_warning(value){
    return {
        "appartament" : value.start_presence_detect[0].appartament,
        "value" : true,
        "type" : "app_presenceStatus"
    }
}

function raise_lack_warning(value){
    return {
        "appartament" : value.start_presence_detect[0].appartament,
        "value" : false,
        "type" : "app_presenceStatus"
    }
}

function appliences_presence_status(value){

    var presenceApps = []
    var swithechedOnCount = 0
    for(var i = 0; i < value.appliences.length; i++){
        if(value.presenseTriggers.contains(value.appliences[i].type)){
            presenceApps = presenceApps.concat(value.appliences[i])
        }
    }
    
    for(var i = 0; i < presenceApps.length; i++){
        if(presenceApps[i].status === true){
            return true
        }
    }

    return false
}

// ,
//         {
//             "pattern" : [
//                 ["_begin", "start_lack_detect"],
//                 ["_where", "is_network"],
//                 ["_where", "wifi_presence_status"],
//                 ["_or", "is_app"], 
//                 ["_where", "appliences_presence_status"],
//                 ["_followedBy", "end_lack_mid"],
//                 ["_where", "is_network"],
//                 ["_where", "not_wifi_presence_status"],
//                 ["_oneOrMore", null],
//                 ["_followedBy", "end_lack_end"],
//                 ["_where", "wifi_presence_status"],
//                 ["_oneOrMore", null],
//                 ["_allowCombinations", null],
//                 ["_whitin", "10"]
//             ]
//         }