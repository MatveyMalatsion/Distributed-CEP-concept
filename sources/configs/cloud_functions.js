
// {"id":"5","type":"security_warning"}
// {"type":"floor_presence_status","value":30.0}
// {"type": "internalSourceEnergyLevel", "value": 0.10451614404279225}
// {"type":"general_illuminance_warning","value":"high"}
// {"type":"floor_presence_status","value":30.0}

var presenceLevel = 0
var illumLevel = "middle"
var energyLevel = 0

function is_presence_status(value){
    return value.type == "floor_presence_status"
}

function apply_precense_level(value){
    presenceLevel = value.value
    return true
}

function apply_illum_level(value){
    illumLevel = value.value
    return true
}

function apply_energy_level(value){
    energyLevel = value.value
    return true
}

function is_internal_source(value){
    return value.type == "internalSourceEnergyLevel" 
}

function is_illuminance_warning(value){
    return value.type == "general_illuminance_warning"
}

function generateStatus(value){
    if (is_enough_energy()){
        return {"buildingId" : 34, "status" : "enough"}
    }else{
        return {"buildingId" : 34, "status" : "not enough"}
    }
}

function is_enough_energy(){
    var constant = 0.1
    var l_component = 0
    var p_component = 0.03 * presenceLevel

    if (illumLevel == "middle"){
        l_component = 0.05
    }else if(illumLevel == "low"){
        l_component = 0.1
    }

    return (constant + l_component + p_component) <= energyLevel
}

function is_not_enough_energy(value){
    return !is_enough_energy
}