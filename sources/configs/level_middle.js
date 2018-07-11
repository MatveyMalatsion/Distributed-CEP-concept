Array.prototype.contains = function(obj) {
    var i = this.length;
    while (i--) {
        if (this[i] === obj) {
            return true;
        }
    }
    return false;
}

presence_statuses = {}

for(var i = 0; i < 30; i ++){
    presence_statuses[i.toString()] = true
}

function is_presence_warning(value){
    return value.type == "app_presenceStatus"
}

function is_illumination_warning(value){
    return value.type == "nat_illumination"
}

function register_presence(value){
    presence_statuses[value.appartament.id] = value.value
    return true
}

function check_security_warning(value){
    return value.appartament.holidayMode === true && value.value === true
}

function check_illumination_low(value){
    return value.value < 0.15
}

function check_illumination_middle(value){
    return value.value >= 0.15 && value.value < 0.6
}

function generate_general_illuminance_warning(value){
    v = value.end[0]
    status = "low"

    if (check_illumination_middle(v)){
        status = "middle"
    }else if(check_illumination_high(v)){
        status = "high"
    }

    return {
        "type" : "general_illuminance_warning",
        value : status
    }
}

function generate_floor_presence_warning(value){
    var keys = Object.keys(presence_statuses)

    presenceCount = 0

    for(var i = 0; i < keys.length; i ++){
        if (presence_statuses[keys[i]] === true){
            presenceCount = presenceCount + 1
        }
    }

    return {
        "type" : "floor_presence_status",
        "value" : presenceCount
    }
}

function check_illumination_high(value){
    return value.value > 0.6
}

function generate_security_warning(value){
    return {
        "type" : "security_warning",
        "id" : value.start[0].appartament.id
    }
}