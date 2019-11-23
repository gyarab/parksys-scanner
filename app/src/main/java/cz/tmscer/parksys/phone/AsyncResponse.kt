package cz.tmscer.parksys.phone

// https://stackoverflow.com/questions/12575068/how-to-get-the-result-of-onpostexecute-to-main-activity-because-asynctask-is-a
interface AsyncResponse<T> {
    fun processFinish(output: T)
}